package com.businesscard.scanner.ui

import android.app.Application
import androidx.lifecycle.*
import com.businesscard.scanner.data.AppDatabase
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.data.BusinessCardRepository
import com.businesscard.scanner.data.InteractionLog
import kotlinx.coroutines.launch
import androidx.lifecycle.MediatorLiveData

enum class SortOrder { NAME_ASC, COMPANY_ASC, DATE_NEWEST, DATE_OLDEST }

class BusinessCardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository: BusinessCardRepository =
        BusinessCardRepository(db.businessCardDao(), db.interactionLogDao())

    val allCards: LiveData<List<BusinessCard>> = repository.allCards

    val duplicateIds: LiveData<Set<Long>> = MediatorLiveData<Set<Long>>().also { mediator ->
        mediator.addSource(allCards) { cards ->
            val ids = mutableSetOf<Long>()
            cards.filter { it.personName.isNotBlank() }
                .groupBy { it.personName.trim().lowercase() }
                .values.filter { it.size > 1 }
                .forEach { group -> group.forEach { ids.add(it.id) } }
            cards.filter { it.email.isNotBlank() }
                .groupBy { it.email.trim().lowercase() }
                .values.filter { it.size > 1 }
                .forEach { group -> group.forEach { ids.add(it.id) } }
            mediator.value = ids
        }
    }

    private val _searchQuery = MutableLiveData<String>("")
    private val _tagFilter   = MutableLiveData<String>("")
    private val _sortOrder   = MutableLiveData<SortOrder>(SortOrder.NAME_ASC)

    val displayCards: LiveData<List<BusinessCard>> = MediatorLiveData<List<BusinessCard>>().also { mediator ->
        var currentSource: LiveData<List<BusinessCard>>? = null
        fun sorted(list: List<BusinessCard>): List<BusinessCard> = when (_sortOrder.value) {
            SortOrder.COMPANY_ASC  -> list.sortedBy { it.companyName.lowercase() }
            SortOrder.DATE_NEWEST  -> list.sortedByDescending { it.createdAt }
            SortOrder.DATE_OLDEST  -> list.sortedBy { it.createdAt }
            else                   -> list.sortedBy { it.personName.lowercase() }
        }
        fun refresh() {
            val query = _searchQuery.value.orEmpty()
            val tag   = _tagFilter.value.orEmpty()
            val source = when {
                tag.isNotBlank()   -> repository.getCardsByTag(tag)
                query.isNotBlank() -> repository.search(query)
                else               -> repository.allCards
            }
            if (currentSource != source) {
                currentSource?.let { mediator.removeSource(it) }
                currentSource = source
                mediator.addSource(source) { mediator.value = sorted(it) }
            } else {
                mediator.value = sorted(mediator.value ?: emptyList())
            }
        }
        mediator.addSource(_searchQuery) { refresh() }
        mediator.addSource(_tagFilter)   { refresh() }
        mediator.addSource(_sortOrder)   { refresh() }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setTagFilter(tag: String)     { _tagFilter.value = tag }
    fun getTagFilter(): String        = _tagFilter.value.orEmpty()
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun getSortOrder(): SortOrder = _sortOrder.value ?: SortOrder.NAME_ASC

    suspend fun getAllTags(): List<String> =
        repository.getAllTagStrings()
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

    fun insert(card: BusinessCard, onResult: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repository.insert(card)
        onResult(id)
    }

    suspend fun insertNow(card: BusinessCard): Long = repository.insert(card)

    fun update(card: BusinessCard) = viewModelScope.launch { repository.update(card) }
    suspend fun updateNow(card: BusinessCard) = repository.update(card)

    fun delete(card: BusinessCard) = viewModelScope.launch { repository.delete(card) }
    suspend fun deleteNow(card: BusinessCard) = repository.delete(card)

    suspend fun mergeNow(updated: BusinessCard, toDelete: BusinessCard) = repository.mergeCards(updated, toDelete)

    suspend fun getAllCardsList() = repository.getAllCardsList()

    suspend fun getCardById(id: Long): BusinessCard? = repository.getCardById(id)

    suspend fun findDuplicate(name: String, email: String) = repository.findDuplicate(name, email)

    suspend fun findExactDuplicate(name: String, email: String) = repository.findExactDuplicate(name, email)

    fun getLogsForCard(cardId: Long) = repository.getLogsForCard(cardId)

    fun insertLog(log: InteractionLog) = viewModelScope.launch { repository.insertLog(log) }

    fun deleteLog(log: InteractionLog) = viewModelScope.launch { repository.deleteLog(log) }
}
