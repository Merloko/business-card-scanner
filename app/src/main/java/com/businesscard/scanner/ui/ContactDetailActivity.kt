package com.businesscard.scanner.ui

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.data.InteractionLog
import com.businesscard.scanner.databinding.ActivityContactDetailBinding
import com.businesscard.scanner.ocr.TextParser
import com.businesscard.scanner.util.VCardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ContactDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactDetailBinding
    private val viewModel: BusinessCardViewModel by viewModels()
    private var card: BusinessCard? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) card?.let { showReminderPicker(it) }
        else Toast.makeText(this, getString(R.string.notification_permission_needed), Toast.LENGTH_LONG).show()
    }

    private val requestRecordAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openMeetingRecorder()
        else Toast.makeText(this, getString(R.string.record_permission_denied), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId == -1L) { finish(); return }

        viewModel.getLogsForCard(cardId).observe(this) { logs ->
            displayLogs(logs)
        }

        binding.btnAddInteraction.setOnClickListener { showAddInteractionDialog() }
        binding.btnSetReminder.setOnClickListener { card?.let { checkAndShowReminderPicker(it) } }
        binding.btnRecordMeeting.setOnClickListener { checkAndOpenMeetingRecorder() }
        binding.btnToggleParseDebug.setOnClickListener {
            val expanded = binding.textParseDebug.visibility == View.VISIBLE
            if (!expanded) {
                card?.let { binding.textParseDebug.text = TextParser.debugParse(it.rawTextFront, it.rawTextBack) }
            }
            binding.textParseDebug.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.btnToggleParseDebug.text = getString(
                if (expanded) R.string.parser_debug_show else R.string.parser_debug_hide
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId != -1L) loadCard(cardId)
    }

    private fun loadCard(cardId: Long) {
        lifecycleScope.launch {
            card = viewModel.getCardById(cardId)
            card?.let { displayCard(it) } ?: finish()
        }
    }

    private fun displayCard(card: BusinessCard) {
        supportActionBar?.title = card.personName.ifBlank { getString(R.string.contact_details) }

        binding.textPersonName.text = card.personName.ifBlank { getString(R.string.fallback_unknown_name) }
        binding.textCompanyName.text = card.companyName.ifBlank { "—" }
        binding.textJobTitle.text = card.jobTitle.ifBlank { "—" }

        setField(binding.rowPhone, binding.textPhone, card.phone)
        setField(binding.rowMobile, binding.textMobile, card.mobile)
        setField(binding.rowEmail, binding.textEmail, card.email)
        setField(binding.rowWebsite, binding.textWebsite, card.website)
        setField(binding.rowAddress, binding.textAddress, card.address)
        setField(binding.rowNotes, binding.textNotes, card.notes)

        val tagList = card.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (tagList.isNotEmpty()) {
            binding.chipGroupDetailTags.removeAllViews()
            tagList.forEach { tag ->
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = tag
                    isClickable = false
                }
                binding.chipGroupDetailTags.addView(chip)
            }
            binding.rowTags.visibility = View.VISIBLE
        } else {
            binding.rowTags.visibility = View.GONE
        }

        val imagePaths = ArrayList<String>()
        if (card.frontImagePath.isNotBlank()) imagePaths.add(card.frontImagePath)
        if (card.backImagePath.isNotBlank()) imagePaths.add(card.backImagePath)

        if (card.frontImagePath.isNotBlank()) {
            Glide.with(this).load(File(card.frontImagePath))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .into(binding.imageFront)
            binding.cardFrontContainer.visibility = View.VISIBLE
            binding.imageFront.setOnClickListener { openImageViewer(imagePaths, 0) }
        } else {
            binding.cardFrontContainer.visibility = View.GONE
        }
        if (card.backImagePath.isNotBlank()) {
            Glide.with(this).load(File(card.backImagePath))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .into(binding.imageBack)
            binding.cardBackContainer.visibility = View.VISIBLE
            val backIndex = if (card.frontImagePath.isNotBlank()) 1 else 0
            binding.imageBack.setOnClickListener { openImageViewer(imagePaths, backIndex) }
        } else {
            binding.cardBackContainer.visibility = View.GONE
        }

        // Tap-to-act: each field launches the appropriate system handler.
        // Uri.fromParts prevents query-parameter injection in mailto: URIs.
        // startActivity is guarded against ActivityNotFoundException on devices without a handler.
        binding.textPhone.setOnClickListener {
            safeLaunch(Intent(Intent.ACTION_DIAL,
                Uri.parse("tel:${VCardUtils.dialable(card.phone.lines().firstOrNull { it.isNotBlank() }.orEmpty())}")))
        }
        binding.textMobile.setOnClickListener {
            safeLaunch(Intent(Intent.ACTION_DIAL,
                Uri.parse("tel:${VCardUtils.dialable(card.mobile.lines().firstOrNull { it.isNotBlank() }.orEmpty())}")))
        }
        binding.textEmail.setOnClickListener {
            safeLaunch(Intent(Intent.ACTION_SENDTO,
                Uri.fromParts("mailto", card.email, null)))
        }
        binding.textWebsite.setOnClickListener {
            val raw = card.website
            val url = if (raw.startsWith("https://") || raw.startsWith("http://")) raw
                      else "https://$raw"
            val parsed = Uri.parse(url)
            val scheme = parsed.scheme ?: ""
            if (scheme != "http" && scheme != "https") return@setOnClickListener
            safeLaunch(Intent(Intent.ACTION_VIEW, parsed))
        }
        binding.textAddress.setOnClickListener {
            safeLaunch(Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(card.address)}")))
        }

        // Fix: also reset to GONE when there is no OCR text, to avoid stale content
        // being visible after navigating from a card that did have OCR data.
        if (card.rawTextFront.isNotBlank() || card.rawTextBack.isNotBlank()) {
            binding.textRawOcr.text = buildString {
                if (card.rawTextFront.isNotBlank()) append("--- Front ---\n${card.rawTextFront}\n\n")
                if (card.rawTextBack.isNotBlank()) append("--- Back ---\n${card.rawTextBack}")
            }
            binding.rawOcrSection.visibility = View.VISIBLE
            binding.parseDebugSection.visibility = View.VISIBLE
        } else {
            binding.rawOcrSection.visibility = View.GONE
            binding.parseDebugSection.visibility = View.GONE
        }
    }

    private fun safeLaunch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayLogs(logs: List<InteractionLog>) {
        binding.logContainer.removeAllViews()
        if (logs.isEmpty()) {
            binding.textNoInteractions.visibility = View.VISIBLE
        } else {
            binding.textNoInteractions.visibility = View.GONE
            val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            for (log in logs) {
                val tv = TextView(this).apply {
                    text = buildString {
                        append("${log.type}  •  ${fmt.format(Date(log.timestamp))}")
                        if (log.note.isNotBlank()) append("\n${log.note}")
                    }
                    textSize = 14f
                    val pad = (8 * resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                    setOnLongClickListener {
                        AlertDialog.Builder(this@ContactDetailActivity)
                            .setTitle(R.string.delete_interaction_title)
                            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteLog(log) }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        true
                    }
                }
                binding.logContainer.addView(tv)
            }
        }
    }

    private fun showAddInteractionDialog() {
        val cardId = card?.id ?: return
        val types = resources.getStringArray(R.array.interaction_types)
        var selectedType = types[0]

        val density = resources.displayMetrics.density
        val sidePad = (24 * density).toInt()
        val topPad = (16 * density).toInt()

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ContactDetailActivity, android.R.layout.simple_spinner_item, types).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedType = types[position]
                }
            }
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.interaction_note_hint)
            maxLines = 3
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePad, topPad, sidePad, 0)
            addView(spinner)
            addView(noteInput)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_interaction_title))
            .setView(container)
            .setPositiveButton(R.string.save_contact) { _, _ ->
                viewModel.insertLog(
                    InteractionLog(cardId = cardId, type = selectedType, note = noteInput.text.toString().trim())
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openImageViewer(paths: ArrayList<String>, startIndex: Int) {
        startActivity(
            Intent(this, ImageViewerActivity::class.java)
                .putStringArrayListExtra(ImageViewerActivity.EXTRA_PATHS, paths)
                .putExtra(ImageViewerActivity.EXTRA_START_INDEX, startIndex)
        )
    }

    private fun setField(row: View, textView: TextView, value: String) {
        if (value.isNotBlank()) {
            textView.text = value
            row.visibility = View.VISIBLE
        } else {
            row.visibility = View.GONE
        }
    }

    private fun saveToPhoneContacts(card: BusinessCard) {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, card.personName)
            putExtra(ContactsContract.Intents.Insert.COMPANY, card.companyName)
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE, card.jobTitle)
            if (card.phone.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, card.phone.lines().firstOrNull { it.isNotBlank() }.orEmpty())
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
            }
            if (card.mobile.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, card.mobile.lines().firstOrNull { it.isNotBlank() }.orEmpty())
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            if (card.email.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.EMAIL, card.email)
                putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
            }
            if (card.address.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.POSTAL, card.address)
                putExtra(ContactsContract.Intents.Insert.POSTAL_TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK)
            }
            if (card.notes.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.NOTES, card.notes)
            }
        }
        safeLaunch(intent)
    }

    private fun shareVCard(card: BusinessCard) {
        val vcard = buildVCardText(card)
        lifecycleScope.launch {
            val file = File(cacheDir, "exports/contact.vcf")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(vcard)
            }
            if (isDestroyed || isFinishing) return@launch
            val uri = FileProvider.getUriForFile(this@ContactDetailActivity, "${packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/x-vcard"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            safeLaunch(Intent.createChooser(shareIntent, getString(R.string.share_contact_via)))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit -> {
                val id = card?.id ?: return false
                startActivity(
                    Intent(this, EditContactActivity::class.java)
                        .putExtra(EditContactActivity.EXTRA_CARD_ID, id)
                )
                true
            }
            R.id.action_qr_code -> {
                card?.let { c -> lifecycleScope.launch { showQrCode(c) } }
                true
            }
            R.id.action_save_contact -> {
                card?.let { saveToPhoneContacts(it) }
                true
            }
            R.id.action_share_vcard -> {
                card?.let { shareVCard(it) }
                true
            }
            R.id.action_linkedin -> {
                card?.let { openLinkedIn(it) }
                true
            }
            R.id.action_delete -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_contact)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        card?.let { viewModel.delete(it) }
                        finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openLinkedIn(card: BusinessCard) {
        val query = listOf(card.personName, card.companyName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (query.isBlank()) {
            Toast.makeText(this, getString(R.string.no_name_to_search), Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("https://www.linkedin.com/search/results/people/?keywords=${Uri.encode(query)}")
        safeLaunch(Intent(Intent.ACTION_VIEW, uri))
    }

    private suspend fun showQrCode(card: BusinessCard) {
        val vcard = buildVCardText(card)
        val bitmap = try {
            withContext(Dispatchers.Default) {
                val size = 512
                val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1)
                val bits = QRCodeWriter().encode(vcard, BarcodeFormat.QR_CODE, size, size, hints)
                Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bmp ->
                    val pixels = IntArray(size * size) { i -> if (bits[i % size, i / size]) Color.BLACK else Color.WHITE }
                    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.qr_generate_failed), Toast.LENGTH_SHORT).show()
            return
        }

        if (isDestroyed || isFinishing) return

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            contentDescription = getString(R.string.card_image_desc)
        }

        AlertDialog.Builder(this)
            .setTitle(card.personName.ifBlank { getString(R.string.contact_details) })
            .setView(imageView)
            .setPositiveButton(R.string.share) { _, _ -> shareVCard(card) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun buildVCardText(card: BusinessCard) = VCardUtils.buildVCardText(card)

    private fun checkAndOpenMeetingRecorder() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            openMeetingRecorder()
        }
    }

    private fun openMeetingRecorder() {
        val id = card?.id ?: return
        startActivity(
            Intent(this, MeetingRecorderActivity::class.java)
                .putExtra(MeetingRecorderActivity.EXTRA_CARD_ID, id)
        )
    }

    private fun checkAndShowReminderPicker(card: BusinessCard) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showReminderPicker(card)
        }
    }

    private fun showReminderPicker(card: BusinessCard) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d)
            TimePickerDialog(this, { _, h, min ->
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                cal.set(Calendar.SECOND, 0)
                scheduleReminder(card, cal.timeInMillis)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun scheduleReminder(card: BusinessCard, triggerMs: Long) {
        val intent = Intent(this, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_CARD_NAME, card.personName)
            putExtra(ReminderReceiver.EXTRA_CARD_ID, card.id)
        }
        val pending = PendingIntent.getBroadcast(
            this, (card.id and 0x7FFFFFFF).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        val exactGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exactGranted) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pending)
        }
        val msg = if (exactGranted) R.string.reminder_set else R.string.reminder_set_inexact
        Toast.makeText(this, getString(msg), Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }
}
