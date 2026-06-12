package com.businesscard.scanner.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.databinding.ActivityMergeDuplicatesBinding
import com.businesscard.scanner.databinding.ItemDuplicatePairBinding
import kotlinx.coroutines.launch

class MergeDuplicatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMergeDuplicatesBinding
    private val viewModel: BusinessCardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMergeDuplicatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.find_duplicates)

        binding.recyclerDuplicates.layoutManager = LinearLayoutManager(this)
        loadDuplicates()
    }

    private fun loadDuplicates() {
        binding.progress.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch {
            val cards = viewModel.getAllCardsList()
            val pairs = withContext(Dispatchers.Default) { findDuplicatePairs(cards) }
            binding.progress.visibility = View.GONE
            if (pairs.isEmpty()) {
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerDuplicates.adapter = null
            } else {
                binding.recyclerDuplicates.adapter = DuplicatePairAdapter(pairs) { a, b ->
                    showMergeDialog(a, b)
                }
            }
        }
    }

    private fun findDuplicatePairs(cards: List<BusinessCard>): List<Pair<BusinessCard, BusinessCard>> {
        val pairs = mutableListOf<Pair<BusinessCard, BusinessCard>>()
        val used = mutableSetOf<Long>()
        for (i in cards.indices) {
            if (cards[i].id in used) continue
            for (j in i + 1 until cards.size) {
                if (cards[j].id in used) continue
                if (areLikelyDuplicates(cards[i], cards[j])) {
                    pairs.add(Pair(cards[i], cards[j]))
                    used.add(cards[i].id)
                    used.add(cards[j].id)
                    break
                }
            }
        }
        return pairs
    }

    private fun areLikelyDuplicates(a: BusinessCard, b: BusinessCard): Boolean {
        val nameMatch = a.personName.isNotBlank() && b.personName.isNotBlank() &&
            a.personName.trim().lowercase() == b.personName.trim().lowercase()
        val emailMatch = a.email.isNotBlank() && b.email.isNotBlank() &&
            a.email.trim().lowercase() == b.email.trim().lowercase()
        return nameMatch || emailMatch
    }

    private fun showMergeDialog(a: BusinessCard, b: BusinessCard) {
        data class FieldDef(val label: String, val fromA: String, val fromB: String)

        val allFields = listOf(
            FieldDef("Name",     a.personName,  b.personName),
            FieldDef("Company",  a.companyName, b.companyName),
            FieldDef("Title",    a.jobTitle,    b.jobTitle),
            FieldDef("Phone",    a.phone,       b.phone),
            FieldDef("Mobile",   a.mobile,      b.mobile),
            FieldDef("Email",    a.email,       b.email),
            FieldDef("Website",  a.website,     b.website),
            FieldDef("Address",  a.address,     b.address),
            FieldDef("Notes",    a.notes,       b.notes),
            FieldDef("Tags",     a.tags,        b.tags)
        ).filter { it.fromA.isNotBlank() || it.fromB.isNotBlank() }

        val choiceMap = mutableMapOf<String, Int>().apply {
            allFields.forEach { put(it.label, 0) }
        }

        val inflater = LayoutInflater.from(this)
        val scrollView = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        scrollView.addView(container)

        allFields.forEach { field ->
            val fieldView = inflater.inflate(R.layout.item_merge_field, container, false)
            fieldView.findViewById<TextView>(R.id.fieldLabel).text = field.label
            val radioGroup = fieldView.findViewById<android.widget.RadioGroup>(R.id.radioGroup)
            fieldView.findViewById<android.widget.RadioButton>(R.id.radioA).text =
                field.fromA.ifBlank { getString(R.string.merge_field_empty) }.take(60)
            fieldView.findViewById<android.widget.RadioButton>(R.id.radioB).text =
                field.fromB.ifBlank { getString(R.string.merge_field_empty) }.take(60)
            radioGroup.check(R.id.radioA)
            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                choiceMap[field.label] = if (checkedId == R.id.radioA) 0 else 1
            }
            container.addView(fieldView)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.merge_contacts)
            .setView(scrollView)
            .setPositiveButton(R.string.btn_merge) { _, _ ->
                fun pick(label: String, valA: String, valB: String) =
                    if ((choiceMap[label] ?: 0) == 1) valB else valA

                val merged = a.copy(
                    personName  = pick("Name",    a.personName,  b.personName),
                    companyName = pick("Company", a.companyName, b.companyName),
                    jobTitle    = pick("Title",   a.jobTitle,    b.jobTitle),
                    phone       = pick("Phone",   a.phone,       b.phone),
                    mobile      = pick("Mobile",  a.mobile,      b.mobile),
                    email       = pick("Email",   a.email,       b.email),
                    website     = pick("Website", a.website,     b.website),
                    address     = pick("Address", a.address,     b.address),
                    notes       = pick("Notes",   a.notes,       b.notes),
                    tags        = pick("Tags",    a.tags,        b.tags)
                )
                lifecycleScope.launch {
                    viewModel.mergeNow(merged, b)
                    android.widget.Toast.makeText(
                        this@MergeDuplicatesActivity, getString(R.string.contacts_merged), android.widget.Toast.LENGTH_SHORT
                    ).show()
                    loadDuplicates()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

private class DuplicatePairAdapter(
    private val pairs: List<Pair<BusinessCard, BusinessCard>>,
    private val onMerge: (BusinessCard, BusinessCard) -> Unit
) : RecyclerView.Adapter<DuplicatePairAdapter.VH>() {

    inner class VH(val binding: ItemDuplicatePairBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemDuplicatePairBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (a, b) = pairs[position]
        val ctx = holder.itemView.context
        holder.binding.nameA.text = a.personName.ifBlank { ctx.getString(R.string.fallback_unknown_name) }
        holder.binding.companyA.text = a.companyName
        holder.binding.emailA.text = a.email
        holder.binding.nameB.text = b.personName.ifBlank { ctx.getString(R.string.fallback_unknown_name) }
        holder.binding.companyB.text = b.companyName
        holder.binding.emailB.text = b.email
        holder.binding.btnMerge.setOnClickListener { onMerge(a, b) }
    }

    override fun getItemCount() = pairs.size
}
