package com.telecomprofi.japanesewalk.hrm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

private const val TAG = "BleHrm"

/** How long a scan-assisted reconnect looks for the strap before giving up. */
private const val RECONNECT_SCAN_MS = 9_000L

/**
 * Client for any standard Bluetooth LE heart-rate strap (Polar, Garmin, Wahoo,
 * Coospo, Magene ... they all implement the same GATT profile).
 *
 * Implements the Bluetooth SIG Heart Rate Service 0x180D: notifications on the
 * Heart Rate Measurement characteristic 0x2A37, plus a one-off read of the
 * Battery Level characteristic 0x2A19 when the strap exposes it.
 *
 * ### Staying connected
 *
 * A chest strap drops out constantly — the wearer turns a corner, the phone goes
 * in a pocket, the contacts dry out. Reconnection therefore has to be robust to
 * its own side effects, which is the subtlety here: closing an abandoned
 * connection itself produces a disconnect callback, so a naive
 * "on disconnect, reconnect" loop schedules a retry for every teardown it
 * performs and the attempts pile up and cancel one another.
 *
 * Three things prevent that:
 *
 *  1. every callback is checked against [activeGatt] and ignored if it belongs to
 *     a connection already discarded;
 *  2. at most one retry is ever pending, posted under [reconnectToken];
 *  3. after a few failed direct connects the strap is probably out of range or
 *     re-advertising, so a short scan precedes the next attempt.
 */
class BleHeartRateMonitor(private val context: Context) : HeartRateSource {

    companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Decode a Heart Rate Measurement value.
         *
         * Byte 0 is a flags field: bit 0 = 16-bit HR, bits 1-2 = sensor contact,
         * bit 3 = energy expended present, bit 4 = RR intervals present.
         */
        fun parseMeasurement(value: ByteArray, nowMs: Long): HrSample? {
            if (value.isEmpty()) return null
            val flags = value[0].toInt() and 0xFF
            val is16Bit = flags and 0x01 != 0
            var offset = 1
            val hr: Int = if (is16Bit) {
                if (value.size < 3) return null
                val v = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                v
            } else {
                if (value.size < 2) return null
                val v = value[offset].toInt() and 0xFF
                offset += 1
                v
            }

            val contact = when ((flags shr 1) and 0x03) {
                0, 1 -> ContactStatus.NOT_SUPPORTED
                2 -> ContactStatus.NO_CONTACT
                else -> ContactStatus.CONTACT
            }

            var energy: Int? = null
            if (flags and 0x08 != 0 && value.size >= offset + 2) {
                energy = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
            }

            val rr = mutableListOf<Int>()
            if (flags and 0x10 != 0) {
                while (value.size >= offset + 2) {
                    val raw = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                    // RR is in 1/1024 s units.
                    rr += (raw * 1000 / 1024)
                    offset += 2
                }
            }

            if (hr <= 0 || hr > 260) return null
            return HrSample(hr, nowMs, contact, energy, rr)
        }
    }

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _state = MutableStateFlow<HrmState>(HrmState.Idle)
    override val state: StateFlow<HrmState> = _state.asStateFlow()

    private val _samples = MutableSharedFlow<HrSample>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: Flow<HrSample> = _samples.asSharedFlow()

    /** The one connection whose callbacks we still care about. */
    private var activeGatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var targetName: String? = null
    private var wantConnection = false
    private var attempt = 0

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectToken = Any()
    private var reconnectScan: ScanCallback? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Remember which strap to use; call [start] to actually connect. */
    fun setDevice(address: String?, name: String?) {
        targetAddress = address
        targetName = name
    }

    override fun start() {
        val address = targetAddress
        if (address == null) {
            _state.value = HrmState.Failed("No heart-rate strap paired yet")
            return
        }
        if (adapter == null) {
            _state.value = HrmState.Failed("This device has no Bluetooth adapter")
            return
        }
        wantConnection = true
        attempt = 0
        if (!isBluetoothOn) {
            // Keep trying: Bluetooth often comes back on mid-walk.
            _state.value = HrmState.Failed("Bluetooth is off")
            scheduleReconnect()
            return
        }
        connectInternal(address)
    }

    override fun stop() {
        wantConnection = false
        handler.removeCallbacksAndMessages(reconnectToken)
        handler.removeCallbacksAndMessages(null)
        stopReconnectScan()
        releaseGatt()
        _state.value = HrmState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        if (!wantConnection) return
        try {
            val device = adapter?.getRemoteDevice(address) ?: run {
                _state.value = HrmState.Failed("Unknown device $address")
                return
            }
            _state.value = HrmState.Connecting(targetName ?: device.name)
            // Discard the old connection first. Its teardown will produce a
            // disconnect callback, which the identity guard then ignores.
            releaseGatt()
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (activeGatt == null) scheduleReconnect()
        } catch (e: SecurityException) {
            _state.value = HrmState.Failed("Bluetooth permission not granted")
            Log.w(TAG, "connect denied", e)
        } catch (e: IllegalArgumentException) {
            _state.value = HrmState.Failed("Invalid strap address")
            Log.w(TAG, "bad address", e)
        }
    }

    /**
     * Drop the current connection and stop caring about its callbacks. Clearing
     * [activeGatt] *before* closing is what makes the identity guard work.
     */
    @SuppressLint("MissingPermission")
    private fun releaseGatt() {
        val gatt = activeGatt ?: return
        activeGatt = null
        closeQuietly(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun closeQuietly(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close denied", e)
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    /**
     * Queue exactly one retry. Backoff stays short at first — most dropouts are
     * momentary — then lengthens so a strap left in a drawer does not keep the
     * radio busy for the whole walk.
     */
    private fun scheduleReconnect() {
        if (!wantConnection) return
        handler.removeCallbacksAndMessages(reconnectToken)
        attempt++
        val delayMs = when {
            attempt <= 3 -> 1_500L
            attempt <= 6 -> 5_000L
            else -> 12_000L
        }
        handler.postDelayed(
            {
                val address = targetAddress
                if (!wantConnection || address == null) return@postDelayed
                if (!isBluetoothOn) {
                    _state.value = HrmState.Failed("Bluetooth is off")
                    scheduleReconnect()
                    return@postDelayed
                }
                // Direct connects stop working once the strap has been away for a
                // while; seeing it advertise again is a better trigger.
                if (attempt > 3) scanThenConnect(address) else connectInternal(address)
            },
            reconnectToken,
            delayMs,
        )
    }

    /** Wait for the strap to advertise, then connect to it. */
    @SuppressLint("MissingPermission")
    private fun scanThenConnect(address: String) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            connectInternal(address)
            return
        }
        stopReconnectScan()
        _state.value = HrmState.Connecting(targetName)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address != address) return
                stopReconnectScan()
                connectInternal(address)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "reconnect scan failed ($errorCode)")
                stopReconnectScan()
                connectInternal(address)
            }
        }
        reconnectScan = callback

        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot start reconnect scan", e)
            reconnectScan = null
            connectInternal(address)
            return
        }

        // Do not scan forever: fall back to a blind connect, then normal backoff.
        handler.postDelayed(
            {
                if (reconnectScan === callback) {
                    stopReconnectScan()
                    if (wantConnection) connectInternal(address)
                }
            },
            reconnectToken,
            RECONNECT_SCAN_MS,
        )
    }

    @SuppressLint("MissingPermission")
    private fun stopReconnectScan() {
        val callback = reconnectScan ?: return
        reconnectScan = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    /**
     * Live scan for straps advertising the Heart Rate Service, for the pairing
     * screen. The flow emits a growing, de-duplicated list sorted by signal.
     */
    @SuppressLint("MissingPermission")
    fun scan(filterByService: Boolean = true): Flow<List<ScannedDevice>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !isBluetoothOn) {
            _state.value = HrmState.Failed(if (adapter == null) "No Bluetooth adapter" else "Bluetooth is off")
            close()
            return@callbackFlow
        }

        val found = linkedMapOf<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = try { result.device.name } catch (e: SecurityException) { null }
                    ?: result.scanRecord?.deviceName
                found[result.device.address] = ScannedDevice(result.device.address, name, result.rssi)
                trySend(found.values.sortedByDescending { it.rssi })
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = HrmState.Failed("Scan failed (code $errorCode)")
                close()
            }
        }

        // Most straps advertise 0x180D, but a few only expose it after connecting,
        // so the UI can fall back to an unfiltered scan.
        val filters = if (filterByService) {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            _state.value = HrmState.Scanning
            scanner.startScan(filters, settings, callback)
        } catch (e: SecurityException) {
            _state.value = HrmState.Failed("Bluetooth scan permission not granted")
            close()
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "stopScan denied", e)
            }
            if (_state.value is HrmState.Scanning) _state.value = HrmState.Idle
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        /** True when this callback belongs to the connection we still want. */
        private fun current(g: BluetoothGatt): Boolean {
            if (g === activeGatt) return true
            // A connection we already walked away from. Close it and say nothing,
            // otherwise its teardown triggers a reconnect we did not ask for.
            closeQuietly(g)
            return false
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        _state.value = HrmState.Failed("Bluetooth permission not granted")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = HrmState.Disconnected(if (status == 0) null else "GATT status $status")
                    releaseGatt()
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!current(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = HrmState.Failed("Service discovery failed ($status)")
                releaseGatt()
                scheduleReconnect()
                return
            }
            val service = g.getService(HEART_RATE_SERVICE)
            val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (characteristic == null) {
                _state.value = HrmState.Failed("Strap does not expose the Heart Rate service")
                return
            }
            try {
                g.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(CCCD)?.let { cccd ->
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                // Only now is the strap genuinely usable, so this is where the
                // backoff resets — not on TCP-level connection.
                attempt = 0
                _state.value = HrmState.Connected(targetName ?: g.device.name)
                g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { g.readCharacteristic(it) }
            } catch (e: SecurityException) {
                _state.value = HrmState.Failed("Bluetooth permission not granted")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!current(g)) return
            if (characteristic.uuid != HEART_RATE_MEASUREMENT) return
            parseMeasurement(value, System.currentTimeMillis())?.let { _samples.tryEmit(it) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!current(g)) return
            if (characteristic.uuid == BATTERY_LEVEL && status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                val pct = value[0].toInt() and 0xFF
                val current = _state.value
                if (current is HrmState.Connected) _state.value = current.copy(batteryPercent = pct)
            }
        }
    }
}
