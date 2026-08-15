package ru.tubek.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DownloadRecord::class,
        SubscriptionEntity::class,
        WatchHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TubekDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile
        private var instance: TubekDatabase? = null

        fun get(context: Context): TubekDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TubekDatabase::class.java,
                    "tubek.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
