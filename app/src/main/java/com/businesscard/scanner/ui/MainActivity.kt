package com.businesscard.scanner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.businesscard.scanner.App
import com.businesscard.scanner.BuildConfig
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.data.VCardParser
import com.businesscard.scanner.databinding.ActivityMainBinding
import com.businesscard.scanner.util.CsvUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: BusinessCardViewModel by viewModels()
    private lateinit var adapter: BusinessCardAdapter
    private var actionMode: ActionMode? = null

    private val pickJsonFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { restoreFromJson(it) }
    }

    // Use "*/*" so both "text/x-vcard" (legacy) and "text/vcard" (RFC 6350) files appear in the picker.
    private val pickVcfFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importVCard(it) }
    }

    private val pickCsvFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = (application as App).crashFile()
        if (crashFile.exists()) {
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    crashFile.readText().take(4096).also { crashFile.delete() }
                }
                val tv = android.widget.TextView(this@MainActivity).apply {
                    text = "CRASH LOG\n\n$msg"
                    setPadding(24, 24, 24, 24)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                val scroll = android.widget.ScrollView(this@MainActivity).apply { addView(tv) }
                setContentView(scroll)
            }
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = BusinessCardAdapter(
            onItemClick = { card ->
                val intent = Intent(this, ContactDetailActivity::class.java)
                intent.putExtra(ContactDetailActivity.EXTRA_CARD_ID, card.id)
                startActivity(intent)
            },
            onDeleteClick = { card -> viewModel.delete(card) },
            onLongPress = { startBatchMode() },
            onSelectionChanged = { ids ->
                actionMode?.title = getString(R.string.n_selected, ids.size)
                if (ids.isEmpty() && adapter.selectionMode) {
                    actionMode?.finish()
                }
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, t: androidx.recyclerview.widget.RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder): Int =
                if (adapter.selectionMode) 0 else super.getSwipeDirs(rv, vh)
            override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == androidx.recyclerview.widget.RecyclerView.NO_POSITION) return
                val card = adapter.currentList.getOrNull(pos) ?: return
                viewModel.delete(card)
                Snackbar.make(binding.root, getString(R.string.contact_deleted, card.personName.ifBlank { getString(R.string.fallback_unknown_name) }), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { viewModel.insert(card) }
                    .show()
            }
        }).attachToRecyclerView(binding.recyclerView)

        viewModel.displayCards.observe(this) { cards ->
            adapter.submitList(cards)
            binding.emptyView.visibility =
                if (cards.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        viewModel.duplicateIds.observe(this) { ids ->
            adapter.updateDuplicateIds(ids)
        }

        viewModel.allCards.observe(this) {
            refreshTagFilter()
        }

        binding.fab.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }

        binding.fabSearch.setOnClickListener {
            if (binding.searchBarCard.visibility == View.VISIBLE) {
                hideSearchBar()
            } else {
                showSearchBar()
            }
        }

        binding.btnClearSearch.setOnClickListener { hideSearchBar() }

        binding.searchEditText.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }

        binding.searchEditText.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
    }

    private fun showSearchBar() {
        binding.searchBarCard.visibility = View.VISIBLE
        binding.searchEditText.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideSearchBar() {
        binding.searchBarCard.visibility = View.GONE
        binding.searchEditText.text?.clear()
        viewModel.setSearchQuery("")
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun startBatchMode() {
        if (actionMode != null) return
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(R.menu.menu_batch, menu)
                mode.title = getString(R.string.n_selected, 0)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.batch_export_csv -> {
                        exportSelectedCsv(adapter.getSelectedCards())
                        mode.finish()
                        true
                    }
                    R.id.batch_delete -> {
                        val cards = adapter.getSelectedCards()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.batch_delete_title, cards.size))
                            .setMessage(R.string.batch_delete_message)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                cards.forEach { viewModel.delete(it) }
                                mode.finish()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                adapter.clearSelection()
                actionMode = null
            }
        })
    }

    private fun exportSelectedCsv(cards: List<BusinessCard>) {
        if (cards.isEmpty()) {
            Toast.makeText(this, getString(R.string.nothing_selected), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = File(cacheDir, "exports/contacts_export.csv")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(CsvUtils.buildCsv(cards))
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Business Card Contacts (${cards.size})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.export_via_chooser)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshTagFilter() {
        lifecycleScope.launch {
            val tags = viewModel.getAllTags()
            if (tags.isEmpty()) {
                binding.tagFilterScroll.visibility = android.view.View.GONE
                return@launch
            }
            binding.tagFilterScroll.visibility = android.view.View.VISIBLE
            val activeTag = viewModel.getTagFilter()
            val chipGroup = binding.chipGroupFilter
            chipGroup.removeAllViews()
            tags.forEach { tag ->
                val chip = com.google.android.material.chip.Chip(this@MainActivity).apply {
                    text = tag
                    isCheckable = true
                    isChecked = (tag == activeTag)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) viewModel.setTagFilter(tag)
                        else viewModel.setTagFilter("")
                    }
                }
                chipGroup.addView(chip)
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val checked = when (viewModel.getSortOrder()) {
            SortOrder.NAME_ASC    -> R.id.sort_name
            SortOrder.COMPANY_ASC -> R.id.sort_company
            SortOrder.DATE_NEWEST -> R.id.sort_newest
            SortOrder.DATE_OLDEST -> R.id.sort_oldest
        }
        menu.findItem(checked)?.isChecked = true
        val nightMode = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        menu.findItem(R.id.theme_system)?.isChecked = nightMode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        menu.findItem(R.id.theme_light)?.isChecked  = nightMode == AppCompatDelegate.MODE_NIGHT_NO
        menu.findItem(R.id.theme_dark)?.isChecked   = nightMode == AppCompatDelegate.MODE_NIGHT_YES
        return super.onPrepareOptionsMenu(menu)
    }

    private fun setNightMode(mode: Int) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("night_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val sortOrder = when (item.itemId) {
            R.id.sort_name    -> SortOrder.NAME_ASC
            R.id.sort_company -> SortOrder.COMPANY_ASC
            R.id.sort_newest  -> SortOrder.DATE_NEWEST
            R.id.sort_oldest  -> SortOrder.DATE_OLDEST
            else -> null
        }
        if (sortOrder != null) {
            viewModel.setSortOrder(sortOrder)
            item.isChecked = true
            return true
        }
        return when (item.itemId) {
            R.id.action_my_card -> {
                startActivity(Intent(this, MyCardActivity::class.java))
                true
            }
            R.id.action_import_vcard -> {
                pickVcfFile.launch("*/*")
                true
            }
            R.id.action_import_csv -> {
                pickCsvFile.launch("*/*")
                true
            }
            R.id.action_merge_duplicates -> {
                startActivity(Intent(this, MergeDuplicatesActivity::class.java))
                true
            }
            R.id.action_export  -> { exportCsv(); true }
            R.id.action_backup  -> { backupJson(); true }
            R.id.action_restore -> { pickJsonFile.launch("application/json"); true }
            R.id.action_about   -> { showAbout(); true }
            R.id.theme_system -> { setNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM); true }
            R.id.theme_light  -> { setNightMode(AppCompatDelegate.MODE_NIGHT_NO); true }
            R.id.theme_dark   -> { setNightMode(AppCompatDelegate.MODE_NIGHT_YES); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Business Card Scanner")
            .setMessage(
                "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                "Built: ${BuildConfig.BUILD_DATE}\n\n" +
                "Scan, store, and search business cards entirely on-device — no account, no cloud, no data leaves your phone.\n\n" +
                "Open source (LGPL v3)\n" +
                "github.com/merloko/business-card-scanner\n\n" +
                "Libraries\n" +
                "• CameraX — Apache 2.0\n" +
                "• ML Kit Text Recognition — Google\n" +
                "• Room Database — Apache 2.0\n" +
                "• Glide — Apache 2.0\n" +
                "• ZXing — Apache 2.0\n" +
                "• Material Components — Apache 2.0"
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.about_view_source) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/merloko/business-card-scanner")))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun backupJson() {
        lifecycleScope.launch {
            val cards = viewModel.getAllCardsList()
            if (cards.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_contacts_to_backup), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val json = withContext(Dispatchers.Default) {
                JSONObject().apply {
                    put("version", 1)
                    put("exportDate", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                    put("contacts", JSONArray().also { arr ->
                        cards.forEach { card ->
                            arr.put(JSONObject().apply {
                                put("personName",    card.personName)
                                put("companyName",   card.companyName)
                                put("jobTitle",      card.jobTitle)
                                put("phone",         card.phone)
                                put("mobile",        card.mobile)
                                put("email",         card.email)
                                put("website",       card.website)
                                put("address",       card.address)
                                put("notes",         card.notes)
                                put("tags",          card.tags)
                                put("rawTextFront",  card.rawTextFront)
                                put("rawTextBack",   card.rawTextBack)
                                put("frontImagePath", "")
                                put("backImagePath", "")
                                put("createdAt",     card.createdAt)
                            })
                        }
                    })
                }.toString(2)
            }
            val file = File(cacheDir, "exports/bizcard_backup_$date.json")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Business Card Backup $date")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.backup_via_chooser)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restoreFromJson(uri: Uri) {
        lifecycleScope.launch {
            try {
                val maxBytes = 10 * 1024 * 1024
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytesWithLimit(maxBytes) }
                } ?: throw Exception("Could not read file or backup exceeds 10 MB limit")
                val text = bytes.toString(Charsets.UTF_8)
                val root = JSONObject(text)
                val arr = root.getJSONArray("contacts")
                var count = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    fun str(key: String) = if (o.has(key)) o.getString(key) else ""
                    fun long(key: String) = if (o.has(key)) o.getLong(key) else System.currentTimeMillis()
                    val card = BusinessCard(
                        personName     = str("personName"),
                        companyName    = str("companyName"),
                        jobTitle       = str("jobTitle"),
                        phone          = str("phone"),
                        mobile         = str("mobile"),
                        email          = str("email"),
                        website        = str("website"),
                        address        = str("address"),
                        notes          = str("notes"),
                        tags           = str("tags"),
                        rawTextFront   = str("rawTextFront"),
                        rawTextBack    = str("rawTextBack"),
                        frontImagePath = str("frontImagePath"),
                        backImagePath  = str("backImagePath"),
                        createdAt      = long("createdAt")
                    )
                    // Use exact (AND) deduplication for bulk import to avoid blocking
                    // different people who share only a name or only an email.
                    val dup = viewModel.findExactDuplicate(card.personName, card.email)
                        ?: if (card.personName.isBlank() || card.email.isBlank())
                            viewModel.findDuplicate(card.personName, card.email) else null
                    if (dup == null) { viewModel.insertNow(card); count++ }
                }
                Toast.makeText(this@MainActivity, getString(R.string.restored_contacts, count), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.restore_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importVCard(uri: Uri) {
        lifecycleScope.launch {
            try {
                val maxBytes = 5 * 1024 * 1024
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytesWithLimit(maxBytes) }
                } ?: throw Exception("Could not read file or file exceeds 5 MB limit")
                val text = bytes.toString(Charsets.UTF_8)
                val parsed = withContext(Dispatchers.Default) { VCardParser.parse(text) }
                var imported = 0
                for (card in parsed) {
                    val dup = viewModel.findExactDuplicate(card.personName, card.email)
                        ?: if (card.personName.isBlank() || card.email.isBlank())
                            viewModel.findDuplicate(card.personName, card.email) else null
                    if (dup == null) { viewModel.insertNow(card); imported++ }
                }
                val msg = if (imported > 0)
                    getString(R.string.import_vcard_success, imported)
                else
                    getString(R.string.import_vcard_none)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.import_vcard_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importCsv(uri: Uri) {
        lifecycleScope.launch {
            try {
                val maxBytes = 5 * 1024 * 1024
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytesWithLimit(maxBytes) }
                        ?: throw Exception("Could not read file or file exceeds 5 MB limit")
                }.toString(Charsets.UTF_8)

                val lines = text.lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) throw Exception("File is empty")

                val headers = CsvUtils.parseCsvLine(lines[0])
                val colMap = CsvUtils.mapCsvHeaders(headers)
                if (colMap.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.import_csv_no_columns), Toast.LENGTH_LONG).show()
                    return@launch
                }

                fun cell(row: List<String>, key: String) = colMap[key]?.let { row.getOrNull(it)?.trim() }.orEmpty()

                var imported = 0
                for (i in 1 until lines.size) {
                    val row = CsvUtils.parseCsvLine(lines[i])
                    if (row.all { it.isBlank() }) continue

                    val firstName = cell(row, "firstName")
                    val lastName  = cell(row, "lastName")
                    val fullName  = cell(row, "fullName")
                    val name = when {
                        fullName.isNotBlank() -> fullName
                        firstName.isNotBlank() || lastName.isNotBlank() ->
                            listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
                        else -> ""
                    }
                    val email = cell(row, "email")

                    val card = BusinessCard(
                        personName  = name,
                        companyName = cell(row, "company"),
                        jobTitle    = cell(row, "jobTitle"),
                        phone       = cell(row, "phone"),
                        mobile      = cell(row, "mobile"),
                        email       = email,
                        website     = cell(row, "website"),
                        address     = cell(row, "address"),
                        notes       = cell(row, "notes"),
                        tags        = cell(row, "tags")
                    )

                    val dup = viewModel.findExactDuplicate(name, email)
                        ?: if (name.isBlank() || email.isBlank()) viewModel.findDuplicate(name, email) else null
                    if (dup == null) { viewModel.insertNow(card); imported++ }
                }

                val msg = if (imported > 0)
                    getString(R.string.import_csv_success, imported)
                else
                    getString(R.string.import_csv_none)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.import_csv_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val cards = viewModel.getAllCardsList()
            if (cards.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_contacts_to_export), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = File(cacheDir, "exports/contacts_export.csv")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(CsvUtils.buildCsv(cards))
            }
            val uri = FileProvider.getUriForFile(
                this@MainActivity,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Business Card Contacts")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.export_via_chooser)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Reads at most `limit` bytes from the stream without allocating beyond that,
    // preventing OOM when opening untrusted files before a size check can run.
    private fun InputStream.readBytesWithLimit(limit: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var totalRead = 0
        var n: Int
        while (read(tmp).also { n = it } != -1) {
            totalRead += n
            if (totalRead > limit) return null
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }
}
