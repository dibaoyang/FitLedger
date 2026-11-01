package com.fitledger.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fitledger.FitApp
import com.fitledger.domain.EnergyUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.time.LocalDate

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FitApp
        return try {
            if (Widgets.ids(app).isEmpty()) return Result.success()
            withContext(Dispatchers.IO) {
                app.dataLock.withLock {
                    // No prepareData/recovery, daily-template application or database writes here.
                    // Snapshot and publish under the same application lock as foreground edits.
                    val snapshot = app.database.withTransaction {
                        val day = LocalDate.now().toEpochDay()
                        val dao = app.database.dao()
                        if (dao.pendingSettings() != null) return@withTransaction WidgetSnapshot(day, status = WidgetStatus.SETTINGS_PENDING)
                        val target = dao.target(day)
                        if (dao.body(day) == null || target == null) return@withTransaction WidgetSnapshot(day, status = WidgetStatus.NEED_WEIGHING)
                        val consumed = dao.allIntakes().filter { it.day == day }.sumOf { it.nutrition.kcal }
                        WidgetSnapshot(day, readEnergyUnit(app), WidgetStatus.READY, consumed, target.nutrition.kcal)
                    }
                    Widgets.render(app, snapshot)
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never leave yesterday's cached nutrition visible when a fresh read fails.
            Widgets.showError(app)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private suspend fun readEnergyUnit(context: Context): EnergyUnit {
        // A normal DataStore read can run preference migrations. Reading only its existing
        // serialized file avoids that side effect and never opens a second DataStore instance.
        val file = context.preferencesDataStoreFile("settings")
        if (!file.exists()) return EnergyUnit.KCAL
        val preferences = file.source().buffer().use { PreferencesSerializer.readFrom(it) }
        return preferences[stringPreferencesKey("energy_unit")]?.let(EnergyUnit::valueOf) ?: EnergyUnit.KCAL
    }
}
