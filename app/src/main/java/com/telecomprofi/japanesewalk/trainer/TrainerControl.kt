package com.telecomprofi.japanesewalk.trainer

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
import com.telecomprofi.japanesewalk.hrm.ScannedDevice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val TAG = "TrainerControl"

sealed interface TrainerState {
    data object Idle : TrainerState
    data object Scanning : TrainerState
    data class Connecting(val name: String?) : TrainerState
    data class Connected(
        val name: String?,
        /** True once the trainer has accepted a Request Control. */
        val hasControl: Boolean = false,
        val powerRange: Ftms.Ranges? = null,
        val resistanceRange: Ftms.Ranges? = null,
        val features: Ftms.Features? = null,
    ) : TrainerState
    data class Disconnected(val reason: String? = null) : TrainerState
    data class Failed(val message: String) : TrainerState

    val isLive: Boolean get() = this is Connected
}

/**
 * Client for a smart trainer over the Bluetooth LE Fitness Machine Service.
 *
 * Reads Indoor Bike Data (power, cadence, speed) and — the point of it —
 * **writes** the target back, so each interval sets its own resistance instead
 * of asking the rider to shift.
 *
 * Two things make this more than a copy of the heart-rate client:
 *
 *  1. **Control has to be requested.** A fitness machine ignores target writes
 *     until it has accepted op code 0x00, and the acceptance arrives as an
 *     indication on the same characteristic, not as a write acknowledgement.
 *  2. **One write at a time.** Android's GATT stack silently drops a write
 *     issued before the previous one's callback, so writes go through a queue
 *     drained by [onCharacteristicWrite].
 *
 * Reconnection follows the same shape as the strap client: an identity guard so
 * a discarded connection's teardown cannot trigger a retry, and at most one
 * retry pending at a time.
 */
class TrainerControl(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString(Ftms.SERVICE)
        val BIKE_DATA: UUID = UUID.fromString(Ftms.INDOOR_BIKE_DATA)
        val CONTROL_POINT: UUID = UUID.fromString(Ftms.CONTROL_POINT)
        val STATUS: UUID = UUID.fromString(Ftms.MACHINE_STATUS)
        val FEATURE: UUID = UUID.fromString(Ftms.MACHINE_FEATURE)
        val POWER_RANGE: UUID = UUID.fromString(Ftms.POWER_RANGE)
        val RESISTANCE_RANGE: UUID = UUID.fromString(Ftms.RESISTANCE_RANGE)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val _state = MutableStateFlow<TrainerState>(TrainerState.Idle)
    val state: StateFlow<TrainerState> = _state.asStateFlow()

    private val _data = MutableSharedFlow<Ftms.BikeData>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val data: Flow<Ftms.BikeData> = _data.asSharedFlow()

    /** Last thing the trainer said about a command, for the Settings readout. */
    private val _lastResponse = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _lastResponse.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var targetName: String? = null
    private var wantConnection = false
    private var attempt = 0

    private val handler = Handler(Looper.getMainLooper())
    private val reconnectToken = Any()

    /**
     * Pending control-point writes; the GATT stack takes exactly one at a time.
     *
     * Guarded by [writeLock] because it is touched from two threads: commands
     * arrive from the engine on the main dispatcher, and the completion callback
     * that drains the next one arrives on a binder thread. An unsynchronised
     * ArrayDeque here loses commands, which for this class means a bout whose
     * resistance never changed.
     */
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private val writeLock = Any()
    private val writeWatchdog = Any()

    /**
     * Whether Set Target Resistance takes a wide (16-bit, 0.1 units) parameter.
     * The spec is ambiguous and trainers differ, so the first `invalid parameter`
     * response flips this and the write is retried.
     */
    private var resistanceWide = false
    private var lastResistanceAsked: Int? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun setDevice(address: String?, name: String?) {
        targetAddress = address
        targetName = name
    }

    fun start() {
        val address = targetAddress
        if (address == null) {
            _state.value = TrainerState.Failed("No trainer paired yet")
            return
        }
        if (adapter == null) {
            _state.value = TrainerState.Failed("This device has no Bluetooth adapter")
            return
        }
        wantConnection = true
        attempt = 0
        if (!isBluetoothOn) {
            _state.value = TrainerState.Failed("Bluetooth is off")
            scheduleReconnect()
            return
        }
        connectInternal(address)
    }

    fun stop() {
        wantConnection = false
        handler.removeCallbacksAndMessages(reconnectToken)
        handler.removeCallbacksAndMessages(writeWatchdog)
        // Leave the trainer free-wheeling rather than stuck at the last target.
        if (activeGatt != null) enqueue(Ftms.stop(pause = false))
        handler.postDelayed({ releaseGatt(); _state.value = TrainerState.Idle }, 250)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        if (!wantConnection) return
        try {
            val device = adapter?.getRemoteDevice(address) ?: run {
                _state.value = TrainerState.Failed("Unknown device $address")
                return
            }
            _state.value = TrainerState.Connecting(targetName ?: device.name)
            releaseGatt()
            synchronized(writeLock) {
                writeQueue.clear()
                writeInFlight = false
            }
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (activeGatt == null) scheduleReconnect()
        } catch (e: SecurityException) {
            _state.value = TrainerState.Failed("Bluetooth permission not granted")
        } catch (e: IllegalArgumentException) {
            _state.value = TrainerState.Failed("Invalid trainer address")
        }
    }

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
        } catch (e: Exception) {
            Log.w(TAG, "close failed", e)
        }
    }

    private fun scheduleReconnect() {
        if (!wantConnection) return
        handler.removeCallbacksAndMessages(reconnectToken)
        attempt++
        val delayMs = when {
            attempt <= 3 -> 2_000L
            attempt <= 6 -> 6_000L
            else -> 15_000L
        }
        handler.postDelayed(
            {
                val address = targetAddress ?: return@postDelayed
                if (!wantConnection) return@postDelayed
                if (!isBluetoothOn) {
                    _state.value = TrainerState.Failed("Bluetooth is off")
                    scheduleReconnect()
                    return@postDelayed
                }
                connectInternal(address)
            },
            reconnectToken,
            delayMs,
        )
    }

    // ------------------------------------------------------------- commands

    fun setTargetPower(watts: Int) {
        val range = (state.value as? TrainerState.Connected)?.powerRange
        val clamped = if (range != null) watts.coerceIn(range.min, range.max) else watts.coerceIn(0, 2000)
        enqueue(Ftms.setTargetPower(clamped))
    }

    fun setTargetResistance(level: Int) {
        val range = (state.value as? TrainerState.Connected)?.resistanceRange
        val clamped = if (range != null) level.coerceIn(range.min, range.max) else level.coerceIn(0, 100)
        lastResistanceAsked = clamped
        enqueue(Ftms.setTargetResistance(clamped, resistanceWide))
    }

    fun resume() = enqueue(Ftms.startOrResume())

    fun pause() = enqueue(Ftms.stop(pause = true))

    private fun enqueue(command: ByteArray) {
        synchronized(writeLock) { writeQueue.addLast(command) }
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        // Block body, not an expression one: the last statement inside the lock
        // is not Unit, and `= synchronized { … }` would infer that as the return
        // type and reject the early returns.
        synchronized(writeLock) {
            if (writeInFlight) return
            val gatt = activeGatt ?: return
            val characteristic = gatt.getService(SERVICE)?.getCharacteristic(CONTROL_POINT) ?: return
            val next = writeQueue.removeFirstOrNull() ?: return
            writeInFlight = true
            try {
                gatt.writeCharacteristic(
                    characteristic,
                    next,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                // A trainer that never acknowledges a write would otherwise
                // stall every later command for the rest of the session.
                handler.removeCallbacksAndMessages(writeWatchdog)
                handler.postDelayed(
                    {
                        val stuck = synchronized(writeLock) {
                            if (writeInFlight) {
                                writeInFlight = false
                                true
                            } else {
                                false
                            }
                        }
                        if (stuck) {
                            Log.w(TAG, "control write never acknowledged; carrying on")
                            drain()
                        }
                    },
                    writeWatchdog,
                    3_000L,
                )
            } catch (e: Exception) {
                Log.w(TAG, "control write failed", e)
                writeInFlight = false
            }
        }
    }

    /** Live scan for trainers advertising the Fitness Machine Service. */
    @SuppressLint("MissingPermission")
    fun scan(filterByService: Boolean = true): Flow<List<ScannedDevice>> = kotlinx.coroutines.flow.callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !isBluetoothOn) {
            _state.value = TrainerState.Failed(
                if (adapter == null) "No Bluetooth adapter" else "Bluetooth is off"
            )
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
                _state.value = TrainerState.Failed("Scan failed (code $errorCode)")
                close()
            }
        }
        val filters = if (filterByService) {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        } else {
            emptyList()
        }
        try {
            _state.value = TrainerState.Scanning
            scanner.startScan(
                filters,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        } catch (e: SecurityException) {
            _state.value = TrainerState.Failed("Bluetooth scan permission not granted")
            close()
            return@callbackFlow
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed", e)
            }
            if (_state.value is TrainerState.Scanning) _state.value = TrainerState.Idle
        }
    }

    // ------------------------------------------------------------- callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        private fun current(g: BluetoothGatt): Boolean {
            if (g === activeGatt) return true
            closeQuietly(g)
            return false
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (!current(g)) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> try {
                    g.discoverServices()
                } catch (e: SecurityException) {
                    _state.value = TrainerState.Failed("Bluetooth permission not granted")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = TrainerState.Disconnected(if (status == 0) null else "GATT status $status")
                    releaseGatt()
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!current(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = TrainerState.Failed("Service discovery failed ($status)")
                releaseGatt()
                scheduleReconnect()
                return
            }
            val service = g.getService(SERVICE)
            if (service == null) {
                _state.value = TrainerState.Failed(
                    "This device does not expose the Fitness Machine service"
                )
                return
            }
            attempt = 0
            _state.value = TrainerState.Connected(targetName ?: g.device.name)

            try {
                // Data first, then the control point's indications, then ask for
                // control. Asking before indications are on means never hearing
                // whether it was granted.
                service.getCharacteristic(BIKE_DATA)?.let { subscribe(g, it, notify = true) }
                service.getCharacteristic(STATUS)?.let { subscribe(g, it, notify = true) }
                service.getCharacteristic(CONTROL_POINT)?.let { subscribe(g, it, notify = false) }
                service.getCharacteristic(FEATURE)?.let { g.readCharacteristic(it) }
            } catch (e: SecurityException) {
                _state.value = TrainerState.Failed("Bluetooth permission not granted")
            }
        }

        @SuppressLint("MissingPermission")
        private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic, notify: Boolean) {
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(CCCD)?.let { cccd ->
                g.writeDescriptor(
                    cccd,
                    if (notify) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    },
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!current(g)) return
            // Once the control point is listening, take control and read the ranges.
            if (descriptor.characteristic.uuid == CONTROL_POINT) {
                enqueue(Ftms.requestControl())
                try {
                    val service = g.getService(SERVICE)
                    service?.getCharacteristic(POWER_RANGE)?.let { g.readCharacteristic(it) }
                    service?.getCharacteristic(RESISTANCE_RANGE)?.let { g.readCharacteristic(it) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "range read denied", e)
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!current(g)) return
            handler.removeCallbacksAndMessages(writeWatchdog)
            synchronized(writeLock) { writeInFlight = false }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "control write status $status")
            }
            drain()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (!current(g)) return
            when (characteristic.uuid) {
                BIKE_DATA -> Ftms.parseIndoorBikeData(value)?.let { _data.tryEmit(it) }
                CONTROL_POINT -> handleControlResponse(value)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (!current(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val connected = _state.value as? TrainerState.Connected ?: return
            _state.value = when (characteristic.uuid) {
                FEATURE -> connected.copy(features = Ftms.parseFeatures(value) ?: connected.features)
                POWER_RANGE -> connected.copy(powerRange = Ftms.parsePowerRange(value) ?: connected.powerRange)
                RESISTANCE_RANGE ->
                    connected.copy(resistanceRange = Ftms.parseResistanceRange(value) ?: connected.resistanceRange)
                else -> connected
            }
        }
    }

    private fun handleControlResponse(value: ByteArray) {
        val response = Ftms.parseControlResponse(value) ?: return
        _lastResponse.value = "0x%02X → %s".format(response.requestOp, Ftms.resultText(response.result))

        if (response.requestOp == Ftms.OP_REQUEST_CONTROL) {
            val connected = _state.value as? TrainerState.Connected
            if (connected != null) _state.value = connected.copy(hasControl = response.ok)
            if (response.ok) enqueue(Ftms.startOrResume())
            return
        }

        // The one ambiguity worth retrying: a rejected resistance parameter is
        // most likely the wrong width, so flip encoding and try once more.
        if (response.requestOp == Ftms.OP_SET_RESISTANCE &&
            response.result == Ftms.RESULT_INVALID_PARAMETER &&
            !resistanceWide
        ) {
            resistanceWide = true
            lastResistanceAsked?.let { enqueue(Ftms.setTargetResistance(it, wide = true)) }
        }
    }
}
