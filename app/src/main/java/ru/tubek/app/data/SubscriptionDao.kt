package ru.tubek.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId LIMIT 1")
    suspend fun getById(channelId: String): SubscriptionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    fun observeIsSubscribed(channelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("UPDATE subscriptions SET notifyEnabled = :enabled WHERE channelId = :channelId")
    suspend fun setNotifyEnabled(channelId: String, enabled: Boolean)

    @Query("UPDATE subscriptions SET lastSeenVideoId = :videoId WHERE channelId = :channelId")
    suspend fun setLastSeenVideoId(channelId: String, videoId: String)
}
