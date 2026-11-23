package com.mystore.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val balance: Double = 0.0,
    val isActive: Boolean = true
)

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val code: String,
    val price: Double = 0.0,
    val isSold: Boolean = false,
    val soldToPhone: String? = null,
    val soldAt: Long? = null
)

@Entity(tableName = "pending_sms")
data class PendingSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderPhone: String,
    val rawBody: String,
    val parsedCategory: String?,
    val parsedAmount: Double?,
    val status: String = "PENDING", // PENDING, SENT, FAILED, IGNORED
    val errorReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sales_log")
data class SaleLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerPhone: String,
    val cardCategory: String,
    val cardCode: String,
    val amount: Double,
    val walletId: Long?,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

