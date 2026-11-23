    @Query("SELECT * FROM cards WHERE category = :category AND isSold = 0 ORDER BY id ASC")
    suspend fun getAvailableCardsByCategory(category: String): List<CardEntity>

    @Query("DELETE FROM cards WHERE category = :category AND isSold = 0")
    suspend fun deleteAvailableCardsByCategory(category: String): Int

    @Query("DELETE FROM cards WHERE id = :cardId")
    suspend fun deleteCardById(cardId: Long): Int

    @Query("UPDATE cards SET code = :newCode WHERE id = :cardId")
    suspend fun updateCardCode(cardId: Long, newCode: String): Int

    @Query("DELETE FROM cards")
    suspend fun clearAllCards(): Int
