package com.telecomprofi.japanesewalk

import android.app.Application
import android.content.Context
import com.telecomprofi.japanesewalk.data.db.AppDatabase
import com.telecomprofi.japanesewalk.data.prefs.AppPrefs
import com.telecomprofi.japanesewalk.data.repo.StatsRepository
import com.telecomprofi.japanesewalk.data.repo.WorkoutRepository
import com.telecomprofi.japanesewalk.game.Notifications
import com.telecomprofi.japanesewalk.game.ReminderScheduler
import com.telecomprofi.japanesewalk.music.MusicLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container. The app is small and single-user; a DI
 * framework would be more ceremony than it is worth.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val db: AppDatabase = AppDatabase.get(appContext)
    val prefs: AppPrefs = AppPrefs(appContext)
    val statsRepository: StatsRepository = StatsRepository(db, prefs)
    val workoutRepository: WorkoutRepository = WorkoutRepository(db, statsRepository)
    val musicLibrary: MusicLibrary = MusicLibrary(appContext, db)
}

object Graph {
    lateinit var container: AppContainer
        private set

    fun init(context: Context) {
        if (!::container.isInitialized) container = AppContainer(context)
    }
}

class JapaneseWalkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.ensureChannels(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val settings = Graph.container.prefs.settings.first()
            ReminderScheduler.apply(this@JapaneseWalkApp, settings.reminders)
        }
    }
}
