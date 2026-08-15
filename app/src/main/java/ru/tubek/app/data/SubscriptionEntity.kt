package ru.tubek.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val channelUrl: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis(),
    val notifyEnabled: Boolean = true,
    val lastSeenVideoId: String? = null
)
