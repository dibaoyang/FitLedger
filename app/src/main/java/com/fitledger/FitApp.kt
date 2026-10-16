package com.fitledger

import android.app.Application
import androidx.room.Room
import com.fitledger.data.*
import com.fitledger.work.Reminders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FitApp: Application() {
    val database by lazy { Room.databaseBuilder(this, LedgerDatabase::class.java, "ledger.db").addMigrations(*Migrations.ALL).build() }
    val repository by lazy { Repository(database) }
    val settings by lazy { Settings(this) }
    val dataLock=Mutex()
    suspend fun completePendingSettings() {
        repository.finishPendingSettings(settings) { p -> Reminders.schedule(this,p.reminder,p.hour,p.minute) }
    }
    suspend fun prepareData() = dataLock.withLock { completePendingSettings() }
}
