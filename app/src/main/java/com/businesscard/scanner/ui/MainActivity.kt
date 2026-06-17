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
import com.businesscard.scanner.ocr.TextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: BusinessCardViewModel by viewModels()
    private lateinit var adapter: BusinessCardAdapter
    private var actionMode: ActionMode? = null

    companion object {
        // Increment when the backup JSON schema changes; restoreFromBackup rejects backups
        // with a higher version than this to prevent silent data corruption on older builds.
        private const val BACKUP_VERSION = 2
    }

    private val pickBackupFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { restoreFromBackup(it) }
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
                viewModel.deleteRowOnly(card)
                Snackbar.make(binding.root, getString(R.string.contact_deleted, card.personName.ifBlank { getString(R.string.fallback_unknown_name) }), Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { viewModel.insert(card) }
                    .addCallback(object : Snackbar.Callback() {
                        override fun onDismissed(snackbar: Snackbar, event: Int) {
                            // Only delete the image files once Undo is no longer possible —
                            // Undo re-inserts this same card object with these same paths.
                            if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) viewModel.cleanupImages(card)
                        }
                    })
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
            R.id.action_export     -> { exportCsv(); true }
            R.id.action_export_ocr_log -> { exportOcrLog(); true }
            R.id.action_backup  -> { backupZip(); true }
            R.id.action_restore -> { pickBackupFile.launch("*/*"); true }
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

    private fun backupZip() {
        lifecycleScope.launch {
            val cards = viewModel.getAllCardsList()
            if (cards.isEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.no_contacts_to_backup), Toast.LENGTH_SHORT).show()
                return@launch
            }
            // Capture once so filename and JSON exportDate always agree.
            val now = Date()
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
            val zipFile = File(cacheDir, "exports/bizcard_backup_$date.zip")
            try {
                withContext(Dispatchers.IO) {
                    zipFile.parentFile?.mkdirs()
                    ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                        // Map absolute image paths → unique relative entry names inside the ZIP.
                        // Deduplicate basenames so two cards whose images share the same filename
                        // don't produce a duplicate ZIP entry (which would silently overwrite on restore).
                        val imageEntries = mutableMapOf<String, String>()
                        val usedEntryNames = mutableSetOf<String>()
                        cards.forEach { card ->
                            for (imgPath in listOf(card.frontImagePath, card.backImagePath)) {
                                if (imgPath.isNotBlank() && imgPath !in imageEntries) {
                                    val imgFile = File(imgPath)
                                    if (imgFile.exists()) {
                                        var entryName = "images/${imgFile.name}"
                                        var suffix = 0
                                        while (entryName in usedEntryNames) {
                                            suffix++
                                            entryName = "images/${imgFile.nameWithoutExtension}_$suffix.${imgFile.extension}"
                                        }
                                        usedEntryNames.add(entryName)
                                        imageEntries[imgPath] = entryName
                                        zos.putNextEntry(ZipEntry(entryName))
                                        imgFile.inputStream().use { it.copyTo(zos) }
                                        zos.closeEntry()
                                    }
                                }
                            }
                        }
                        // Embed JSON with relative image paths
                        val json = JSONObject().apply {
                            put("version", BACKUP_VERSION)
                            put("exportDate", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now))
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
                                        put("frontImagePath", imageEntries[card.frontImagePath] ?: "")
                                        put("backImagePath",  imageEntries[card.backImagePath] ?: "")
                                        put("createdAt",     card.createdAt)
                                    })
                                }
                            })
                        }.toString(2)
                        zos.putNextEntry(ZipEntry("backup.json"))
                        zos.write(json.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.backup_failed, e.message), Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
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

    private fun restoreFromBackup(uri: Uri) {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    // Open the content URI exactly once. BufferedInputStream.mark/reset allows
                    // format detection without a second openInputStream call, which would fail
                    // on single-read SAF URIs (e.g. some cloud storage providers).
                    val stream = contentResolver.openInputStream(uri)?.buffered()
                        ?: throw Exception("Could not open backup file")
                    stream.use { s ->
                        s.mark(2)
                        val b0 = s.read()
                        val b1 = s.read()
                        val isZip = b0 == 0x50 && b1 == 0x4B
                        s.reset()
                        if (isZip) restoreFromZip(s) else restoreFromLegacyJson(s)
                    }
                }
                Toast.makeText(this@MainActivity, getString(R.string.restored_contacts, count), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.restore_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun restoreFromZip(stream: InputStream): Int {
        val imageEntryToPath = mutableMapOf<String, String>()
        var jsonBytes: ByteArray? = null
        val filesCanonical = filesDir.canonicalPath + File.separator
        var totalImageBytes = 0L
        val imageByteLimit = 2L * 1024 * 1024 * 1024 // 2 GB total across all images

        // Wrap in a non-closing delegate: ZipInputStream.close() would otherwise close
        // the underlying stream, which the caller's own stream.use{} also closes —
        // a double-close that throws on some cloud-backed SAF providers.
        ZipInputStream(object : FilterInputStream(stream) { override fun close() {} }).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name.startsWith("images/") -> {
                            val imageName = entry.name.removePrefix("images/")
                            val dest = File(filesDir, imageName)
                            // Path-traversal guard: reject any entry that escapes filesDir.
                            if (dest.canonicalPath.startsWith(filesCanonical)) {
                                dest.parentFile?.mkdirs()
                                dest.outputStream().use { out ->
                                    val buf = ByteArray(8192)
                                    var n: Int
                                    while (zis.read(buf).also { n = it } != -1) {
                                        totalImageBytes += n
                                        if (totalImageBytes > imageByteLimit) {
                                            throw Exception("Backup images exceed 2 GB limit")
                                        }
                                        out.write(buf, 0, n)
                                    }
                                }
                                imageEntryToPath[entry.name] = dest.absolutePath
                            }
                        }
                        entry.name == "backup.json" -> {
                            jsonBytes = zis.readBytesWithLimit(50 * 1024 * 1024)
                                ?: throw Exception("backup.json exceeds 50 MB limit")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val text = jsonBytes?.toString(Charsets.UTF_8)
            ?: throw Exception("backup.json not found in archive")
        return parseAndInsertCards(text, imageEntryToPath)
    }

    private suspend fun restoreFromLegacyJson(stream: InputStream): Int {
        val bytes = stream.readBytesWithLimit(10 * 1024 * 1024)
            ?: throw Exception("Could not read file or backup exceeds 10 MB limit")
        return parseAndInsertCards(bytes.toString(Charsets.UTF_8), emptyMap())
    }

    private suspend fun parseAndInsertCards(
        jsonText: String,
        imageEntryToPath: Map<String, String>
    ): Int {
        val root = JSONObject(jsonText)
        // Forward-compatibility guard: refuse backups from newer app versions that may
        // have an incompatible schema, rather than silently importing garbled data.
        val version = if (root.has("version")) root.getInt("version") else 1
        if (version > BACKUP_VERSION) {
            throw Exception("Backup was created by a newer version of the app (v$version). Please update the app to restore this backup.")
        }
        val arr = root.getJSONArray("contacts")

        // Pre-load existing records once to avoid N×2 Room round-trips inside the import loop.
        val existing = viewModel.getAllCardsList()
        val exactKeys = existing
            .map { "${it.personName.trim().lowercase()}|${it.email.trim().lowercase()}" }
            .toHashSet()
        val existingNames  = existing.mapTo(HashSet()) { it.personName.trim().lowercase() }.also { it.remove("") }
        val existingEmails = existing.mapTo(HashSet()) { it.email.trim().lowercase() }.also { it.remove("") }

        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun str(key: String) = o.optString(key, "")
            fun long(key: String) = if (o.has(key)) o.getLong(key) else System.currentTimeMillis()
            val frontRel = str("frontImagePath")
            val backRel  = str("backImagePath")
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
                frontImagePath = imageEntryToPath[frontRel] ?: "",
                backImagePath  = imageEntryToPath[backRel]  ?: "",
                createdAt      = long("createdAt")
            )
            val nameL  = card.personName.trim().lowercase()
            val emailL = card.email.trim().lowercase()
            // AND-logic dedup: exact match on both fields; fall back to single-field when one is blank.
            // When both are blank there's no signal to compare on, so the card is always
            // treated as new (matches findExactDuplicate's contract, which never matches
            // a blank/blank pair) rather than collapsing every blank contact into one.
            val isDup = when {
                nameL.isNotEmpty() && emailL.isNotEmpty() -> "$nameL|$emailL" in exactKeys
                else -> (nameL.isNotEmpty() && nameL in existingNames) ||
                        (emailL.isNotEmpty() && emailL in existingEmails)
            }
            if (!isDup) {
                viewModel.insertNow(card)
                count++
                // Update local sets so later entries in the same import don't re-insert duplicates.
                exactKeys.add("$nameL|$emailL")
                if (nameL.isNotEmpty()) existingNames.add(nameL)
                if (emailL.isNotEmpty()) existingEmails.add(emailL)
            }
        }
        return count
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

                val csvRows = CsvUtils.parseCsvRows(text)
                if (csvRows.isEmpty()) throw Exception("File is empty")

                val headers = csvRows[0]
                val colMap = CsvUtils.mapCsvHeaders(headers)
                if (colMap.isEmpty()) {
                    Toast.makeText(this@MainActivity, getString(R.string.import_csv_no_columns), Toast.LENGTH_LONG).show()
                    return@launch
                }

                fun cell(row: List<String>, key: String) = colMap[key]?.let { row.getOrNull(it)?.trim() }.orEmpty()

                var imported = 0
                for (i in 1 until csvRows.size) {
                    val row = csvRows[i]
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

    private fun exportOcrLog() {
        lifecycleScope.launch {
            val cards = viewModel.getAllCardsList()
                .filter { it.rawTextFront.isNotBlank() || it.rawTextBack.isNotBlank() }
            if (cards.isEmpty()) {
                Toast.makeText(this@MainActivity, "No cards with OCR data to export", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val json = withContext(Dispatchers.Default) {
                JSONArray().also { arr ->
                    cards.forEach { card ->
                        val parsed = TextParser.parse(card.rawTextFront, card.rawTextBack)
                        arr.put(JSONObject().apply {
                            put("id", card.id)
                            put("createdAt", dateFmt.format(Date(card.createdAt)))
                            put("saved", JSONObject().apply {
                                put("name",    card.personName)
                                put("company", card.companyName)
                                put("title",   card.jobTitle)
                                put("phone",   card.phone)
                                put("mobile",  card.mobile)
                                put("email",   card.email)
                                put("website", card.website)
                                put("address", card.address)
                            })
                            put("parser", JSONObject().apply {
                                put("name",    parsed.personName)
                                put("company", parsed.companyName)
                                put("title",   parsed.jobTitle)
                                put("phone",   parsed.phone)
                                put("mobile",  parsed.mobile)
                                put("email",   parsed.email)
                                put("website", parsed.website)
                                put("address", parsed.address)
                            })
                            put("rawFront", card.rawTextFront)
                            put("rawBack",  card.rawTextBack)
                        })
                    }
                }.toString(2)
            }
            val file = File(cacheDir, "exports/ocr_log.json")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OCR Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, "Share OCR Log"))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
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
