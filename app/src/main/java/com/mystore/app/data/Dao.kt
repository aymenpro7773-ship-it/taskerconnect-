package com.mystore.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface WalletDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity): Long

    @Update
    suspend fun updateWallet(wallet: WalletEntity)

    @Query("SELECT * FROM wallets ORDER BY id ASC")
    suspend fun getAllWallets(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE isActive = 1 ORDER BY id ASC LIMIT 1")
    suspend fun getActiveWallet(): WalletEntity?
}

@Dao
interface CardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCards(cards: List<CardEntity>): List<Long>

    @Query("SELECT * FROM cards WHERE category = :category AND isSold = 0 ORDER BY id ASC LIMIT 1")
    suspend fun getNextAvailableCard(category: String): CardEntity?

    @Query("UPDATE cards SET isSold = 1, soldToPhone = :phone, soldAt = :soldAt WHERE id = :cardId AND isSold = 0")
    suspend fun markCardSold(cardId: Long, phone: String, soldAt: Long): Int

    @Query("SELECT category, COUNT(*) AS availableCount FROM cards WHERE isSold = 0 GROUP BY category")
    suspend fun getAvailableCountsByCategory(): List<CategoryStockSummary>

    @Query("SELECT COUNT(*) FROM cards WHERE isSold = 0")
    suspend fun getTotalAvailableCards(): Int
}

data class CategoryStockSummary(
    val category: String,
    val availableCount: Int
)

@Dao
interface PendingSmsDao {
    @Insert
    suspend fun insertPending(sms: PendingSmsEntity): Long

    @Query("SELECT * FROM pending_sms WHERE status = 'PENDING' ORDER BY id ASC")
    suspend fun getPendingSmsList(): List<PendingSmsEntity>

    @Query("UPDATE pending_sms SET status = :status, errorReason = :reason, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, reason: String?, updatedAt: Long)

    @Query("SELECT * FROM pending_sms ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentSms(limit: Int = 30): List<PendingSmsEntity>
}

@Dao
interface SaleLogDao {
    @Insert
    suspend fun insertSale(sale: SaleLogEntity): Long

    @Query("SELECT * FROM sales_log WHERE timestamp >= :sinceMillis ORDER BY timestamp DESC")
    suspend fun getSalesSince(sinceMillis: Long): List<SaleLogEntity>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM sales_log WHERE timestamp >= :sinceMillis")
    suspend fun getTotalRevenueSince(sinceMillis: Long): Double

    @Query("SELECT COUNT(*) FROM sales_log WHERE timestamp >= :sinceMillis")
    suspend fun getSalesCountSince(sinceMillis: Long): Int
}

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettingEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?
}
