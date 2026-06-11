package com.businesscard.scanner.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BusinessCardDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: BusinessCard): Long

    @Update
    suspend fun update(card: BusinessCard)

    @Delete
    suspend fun delete(card: BusinessCard)

    @Query("SELECT * FROM business_cards ORDER BY createdAt DESC")
    fun getAllCards(): LiveData<List<BusinessCard>>

    @Query("SELECT * FROM business_cards ORDER BY createdAt DESC")
    suspend fun getAllCardsList(): List<BusinessCard>

    @Query("SELECT * FROM business_cards WHERE id = :id")
    suspend fun getCardById(id: Long): BusinessCard?

    @Query("""
        SELECT * FROM business_cards
        WHERE personName LIKE '%' || :query || '%'
        OR companyName LIKE '%' || :query || '%'
        OR tags LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
    """)
    fun search(query: String): LiveData<List<BusinessCard>>

    @Query("SELECT * FROM business_cards WHERE (personName != '' AND LOWER(personName) = LOWER(:name)) OR (email != '' AND LOWER(email) = LOWER(:email)) LIMIT 1")
    suspend fun findDuplicate(name: String, email: String): BusinessCard?

    // Stricter AND-logic duplicate check used during bulk import to avoid false positives
    // when two different people share a name but have distinct emails (or vice versa).
    // Returns a match only when both name AND email are non-blank and both match.
    @Query("""SELECT * FROM business_cards WHERE
        personName != '' AND :name != '' AND LOWER(personName) = LOWER(:name)
        AND email != '' AND :email != '' AND LOWER(email) = LOWER(:email)
        LIMIT 1""")
    suspend fun findExactDuplicate(name: String, email: String): BusinessCard?

    @Query("SELECT * FROM business_cards WHERE ',' || tags || ',' LIKE '%,' || :tag || ',%' ORDER BY createdAt DESC")
    fun getCardsByTag(tag: String): LiveData<List<BusinessCard>>

    @Transaction
    suspend fun mergeCards(updated: BusinessCard, toDelete: BusinessCard) {
        update(updated)
        delete(toDelete)
    }

    @Query("SELECT DISTINCT tags FROM business_cards WHERE tags != ''")
    suspend fun getAllTagStrings(): List<String>

    @Query("SELECT COUNT(*) FROM business_cards")
    suspend fun getCount(): Int
}
