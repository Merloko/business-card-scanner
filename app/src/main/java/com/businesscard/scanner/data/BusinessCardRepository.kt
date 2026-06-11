package com.businesscard.scanner.data

import androidx.lifecycle.LiveData
import com.businesscard.scanner.ocr.AutoTagger

class BusinessCardRepository(
    private val dao: BusinessCardDao,
    private val logDao: InteractionLogDao
) {

    val allCards: LiveData<List<BusinessCard>> = dao.getAllCards()

    suspend fun insert(card: BusinessCard): Long {
        val suggested = AutoTagger.suggestTags(card)
        val augmented = if (suggested.isNotEmpty()) {
            val existing = card.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val merged = (existing + suggested).distinct().joinToString(",")
            card.copy(tags = merged)
        } else card
        return dao.insert(augmented)
    }

    suspend fun update(card: BusinessCard) = dao.update(card)

    suspend fun delete(card: BusinessCard) = dao.delete(card)

    suspend fun getAllCardsList(): List<BusinessCard> = dao.getAllCardsList()

    suspend fun getCardById(id: Long): BusinessCard? = dao.getCardById(id)

    fun search(query: String): LiveData<List<BusinessCard>> = dao.search(query)

    suspend fun findDuplicate(name: String, email: String) = dao.findDuplicate(name, email)

    suspend fun findExactDuplicate(name: String, email: String) = dao.findExactDuplicate(name, email)

    fun getCardsByTag(tag: String) = dao.getCardsByTag(tag)

    suspend fun mergeCards(updated: BusinessCard, toDelete: BusinessCard) = dao.mergeCards(updated, toDelete)

    suspend fun getAllTagStrings() = dao.getAllTagStrings()

    fun getLogsForCard(cardId: Long): LiveData<List<InteractionLog>> = logDao.getLogsForCard(cardId)

    suspend fun insertLog(log: InteractionLog): Long = logDao.insert(log)

    suspend fun deleteLog(log: InteractionLog) = logDao.delete(log)
}
