package com.businesscard.scanner.ui

import android.app.Application
import androidx.lifecycle.*
import com.businesscard.scanner.data.AppDatabase
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.data.BusinessCardRepository
import com.businesscard.scanner.data.InteractionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.MediatorLiveData
import java.io.File

enum class SortOrder { NAME_ASC, COMPANY_ASC, DATE_NEWEST, DATE_OLDEST }

class BusinessCardViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repository: BusinessCardRepository =
        BusinessCardRepository(db.businessCardDao(), db.interactionLogDao())

    val allCards: LiveData<List<BusinessCard>> = repository.allCards

    val duplicateIds: LiveData<Set<Long>> = MediatorLiveData<Set<Long>>().also { mediator ->
        mediator.addSource(allCards) { cards ->
            val byName  = mutableMapOf<String, MutableList<Long>>()
            val byEmail = mutableMapOf<String, MutableList<Long>>()
            for (card in cards) {
                if (card.personName.isNotBlank()) byName.getOrPut(card.personName.trim().lowercase()) { mutableListOf() }.add(card.id)
                if (card.email.isNotBlank())      byEmail.getOrPut(card.email.trim().lowercase())     { mutableListOf() }.add(card.id)
            }
            mediator.value = (byName.values + byEmail.values)
                .filter { it.size > 1 }
                .flatten()
                .toSet()
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
                tag.isNotBlank()   -> repository.getCardsByTag(escapeLike(tag))
                query.isNotBlank() -> repository.search(escapeLike(query))
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

    // Escapes SQL LIKE wildcard characters so a literal '%' or '_' typed by the user
    // doesn't get interpreted as a wildcard (paired with ESCAPE '\' in BusinessCardDao.search).
    private fun escapeLike(s: String) = s
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setTagFilter(tag: String)     { _tagFilter.value = tag }
    fun getTagFilter(): String        = _tagFilter.value.orEmpty()
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun getSortOrder(): SortOrder = _sortOrder.value ?: SortOrder.NAME_ASC

    fun getAllTags(): List<String> =
        (allCards.value ?: emptyList())
            .flatMap { it.tags.split(",") }
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

    fun delete(card: BusinessCard) = viewModelScope.launch { deleteNow(card) }
    suspend fun deleteNow(card: BusinessCard) {
        repository.delete(card)
        deleteImageFiles(card.frontImagePath, card.backImagePath)
    }

    // Used by swipe-to-delete, which offers an Undo action: removes only the DB row so
    // the UI updates immediately, but leaves the image files in place since Undo
    // re-inserts the same card object pointing at those same paths. Call cleanupImages
    // once the Undo window has passed (see MainActivity's Snackbar.Callback).
    fun deleteRowOnly(card: BusinessCard) = viewModelScope.launch { repository.delete(card) }
    fun cleanupImages(card: BusinessCard) = viewModelScope.launch {
        deleteImageFiles(card.frontImagePath, card.backImagePath)
    }

    // toDelete's image paths may be the very files `updated` now points to (when the
    // surviving card had no image of its own and reused the merged-away card's path),
    // so only delete files that aren't still referenced by the merged result.
    suspend fun mergeNow(updated: BusinessCard, toDelete: BusinessCard) {
        repository.mergeCards(updated, toDelete)
        withContext(Dispatchers.IO) {
            if (toDelete.frontImagePath.isNotBlank() && toDelete.frontImagePath != updated.frontImagePath) {
                File(toDelete.frontImagePath).delete()
            }
            if (toDelete.backImagePath.isNotBlank() && toDelete.backImagePath != updated.backImagePath) {
                File(toDelete.backImagePath).delete()
            }
        }
    }

    private suspend fun deleteImageFiles(frontImagePath: String, backImagePath: String) = withContext(Dispatchers.IO) {
        if (frontImagePath.isNotBlank()) File(frontImagePath).delete()
        if (backImagePath.isNotBlank()) File(backImagePath).delete()
    }

    suspend fun getAllCardsList() = repository.getAllCardsList()

    suspend fun getCardById(id: Long): BusinessCard? = repository.getCardById(id)

    suspend fun findDuplicate(name: String, email: String) = repository.findDuplicate(name, email)

    suspend fun findExactDuplicate(name: String, email: String) = repository.findExactDuplicate(name, email)

    fun getLogsForCard(cardId: Long) = repository.getLogsForCard(cardId)

    fun insertLog(log: InteractionLog) = viewModelScope.launch { repository.insertLog(log) }

    fun deleteLog(log: InteractionLog) = viewModelScope.launch { repository.deleteLog(log) }
}
