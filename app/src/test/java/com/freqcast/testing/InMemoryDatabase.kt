package com.freqcast.testing

import androidx.room.Room
import com.freqcast.data.AppDatabase
import org.robolectric.RuntimeEnvironment

/**
 * In-memory [AppDatabase] whose suspend DAO calls run inline on the calling thread, so a
 * ViewModel's `viewModelScope.launch { repository... }` settles within one main-looper idle
 * (`composeTestRule.waitForIdle()`) instead of hopping onto Room's own executor.
 */
fun inMemoryDatabase(): AppDatabase =
    Room
        .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
