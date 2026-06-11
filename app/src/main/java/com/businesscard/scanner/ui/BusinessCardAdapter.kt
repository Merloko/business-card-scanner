package com.businesscard.scanner.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.databinding.ItemBusinessCardBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class BusinessCardAdapter(
    private val onItemClick: (BusinessCard) -> Unit,
    private val onDeleteClick: (BusinessCard) -> Unit,
    private val onLongPress: (BusinessCard) -> Unit = {},
    private val onSelectionChanged: (selectedIds: Set<Long>) -> Unit = {}
) : ListAdapter<BusinessCard, BusinessCardAdapter.ViewHolder>(DiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()
    var selectionMode = false
        private set

    private var duplicateIds: Set<Long> = emptySet()

    fun updateDuplicateIds(ids: Set<Long>) {
        duplicateIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    fun startSelectionMode() {
        selectionMode = true
    }

    fun clearSelection() {
        selectionMode = false
        selectedIds.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    fun getSelectedCards(): List<BusinessCard> =
        currentList.filter { it.id in selectedIds }

    inner class ViewHolder(private val binding: ItemBusinessCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: BusinessCard) {
            binding.textName.text = card.personName.ifBlank { binding.root.context.getString(R.string.fallback_unknown_name) }
            binding.textCompany.text = card.companyName.ifBlank { binding.root.context.getString(R.string.fallback_no_company) }
            binding.textJob.text = card.jobTitle
            binding.textPhone.text = card.phone
            binding.textEmail.text = card.email

            binding.textDate.text = Instant.ofEpochMilli(card.createdAt)
                .atZone(ZoneId.systemDefault())
                .format(DATE_FORMATTER)

            binding.avatarText.text = buildInitials(card.personName)
            binding.btnDelete.visibility = if (selectionMode) android.view.View.GONE else android.view.View.VISIBLE

            binding.badgeDuplicate.visibility =
                if (card.id in duplicateIds) android.view.View.VISIBLE else android.view.View.GONE
            val needsRescan = card.personName.isBlank() &&
                (card.frontImagePath.isNotBlank() || card.backImagePath.isNotBlank())
            binding.badgeRescan.visibility =
                if (needsRescan) android.view.View.VISIBLE else android.view.View.GONE

            val cardView = binding.root
            cardView.isCheckable = selectionMode
            cardView.isChecked = card.id in selectedIds

            binding.root.setOnLongClickListener {
                if (!selectionMode) {
                    onLongPress(card)
                    selectionMode = true
                    toggleSelection(card)
                }
                true
            }

            binding.root.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(card)
                } else {
                    onItemClick(card)
                }
            }

            binding.btnDelete.setOnClickListener { onDeleteClick(card) }
        }

        private fun toggleSelection(card: BusinessCard) {
            if (card.id in selectedIds) selectedIds.remove(card.id)
            else selectedIds.add(card.id)
            val pos = bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            onSelectionChanged(selectedIds.toSet())
        }

        private fun buildInitials(name: String): String {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            return when {
                parts.size >= 2 -> "${parts[0][0]}${parts[1][0]}".uppercase()
                parts.size == 1 -> parts[0][0].uppercase()
                else -> "?"
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBusinessCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<BusinessCard>() {
        override fun areItemsTheSame(oldItem: BusinessCard, newItem: BusinessCard) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: BusinessCard, newItem: BusinessCard) =
            oldItem == newItem
    }

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter
            .ofPattern("dd MMM yyyy")
            .withLocale(Locale.getDefault())
    }
}
