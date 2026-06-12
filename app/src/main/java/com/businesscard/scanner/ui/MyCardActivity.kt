package com.businesscard.scanner.ui

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.nfc.NdefMessage
import android.os.Build
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.businesscard.scanner.R
import com.businesscard.scanner.databinding.ActivityMyCardBinding
import com.businesscard.scanner.ocr.CjkUtils
import com.businesscard.scanner.util.VCardUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MyCardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyCardBinding
    private val prefs by lazy { getSharedPreferences("my_card", MODE_PRIVATE) }

    private var nfcAdapter: NfcAdapter? = null
    @Volatile private var nfcWritePending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyCardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.my_card_title)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        loadFromPrefs()

        binding.btnSave.setOnClickListener {
            saveToPrefs()
            Toast.makeText(this, getString(R.string.my_card_saved), Toast.LENGTH_SHORT).show()
        }
        binding.btnShowQr.setOnClickListener { saveToPrefs(); lifecycleScope.launch { showQrCode() } }
        binding.btnShare.setOnClickListener { saveToPrefs(); shareVCard() }
        binding.btnSaveToContacts.setOnClickListener { saveToPrefs(); saveToContacts() }
        binding.btnNfcShare.setOnClickListener { saveToPrefs(); startNfcWrite() }
    }

    override fun onResume() {
        super.onResume()
        if (nfcWritePending) enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!nfcWritePending) return
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        writeVCardToTag(tag)
    }

    private fun startNfcWrite() {
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.nfc_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        if (binding.editName.text.toString().trim().isBlank()) {
            Toast.makeText(this, getString(R.string.my_card_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        nfcWritePending = true
        Toast.makeText(this, getString(R.string.nfc_hold_to_tag), Toast.LENGTH_LONG).show()
        enableNfcForegroundDispatch()
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        adapter.enableForegroundDispatch(this, pending, null, null)
    }

    private fun writeVCardToTag(tag: Tag) {
        val vcard = buildVCard()
        val record = NdefRecord.createMime("text/x-vcard", vcard.toByteArray(Charsets.UTF_8))
        val message = NdefMessage(record)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ndef = Ndef.get(tag)
                if (ndef != null) {
                    ndef.connect()
                    if (!ndef.isWritable) throw Exception("Tag is read-only")
                    if (ndef.maxSize < message.byteArrayLength) throw Exception("Tag capacity too small")
                    ndef.writeNdefMessage(message)
                    ndef.close()
                } else {
                    val formatable = NdefFormatable.get(tag) ?: throw Exception("Tag not NDEF-compatible")
                    formatable.connect()
                    formatable.format(message)
                    formatable.close()
                }
                withContext(Dispatchers.Main) {
                    nfcWritePending = false
                    Toast.makeText(this@MyCardActivity, getString(R.string.nfc_write_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MyCardActivity, getString(R.string.nfc_write_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveToContacts() {
        val name = binding.editName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.my_card_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            putExtra(ContactsContract.Intents.Insert.COMPANY, binding.editCompany.text.toString().trim())
            putExtra(ContactsContract.Intents.Insert.JOB_TITLE, binding.editTitle.text.toString().trim())
            val phone = binding.editPhone.text.toString().trim()
            if (phone.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, phone)
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
            }
            val mobile = binding.editMobile.text.toString().trim()
            if (mobile.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, mobile)
                putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            val email = binding.editEmail.text.toString().trim()
            if (email.isNotBlank()) {
                putExtra(ContactsContract.Intents.Insert.EMAIL, email)
                putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
            }
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_action), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadFromPrefs() {
        binding.editName.setText(prefs.getString("name", ""))
        binding.editCompany.setText(prefs.getString("company", ""))
        binding.editTitle.setText(prefs.getString("title", ""))
        binding.editPhone.setText(prefs.getString("phone", ""))
        binding.editMobile.setText(prefs.getString("mobile", ""))
        binding.editEmail.setText(prefs.getString("email", ""))
        binding.editWebsite.setText(prefs.getString("website", ""))
    }

    private fun saveToPrefs() {
        prefs.edit()
            .putString("name", binding.editName.text.toString().trim())
            .putString("company", binding.editCompany.text.toString().trim())
            .putString("title", binding.editTitle.text.toString().trim())
            .putString("phone", binding.editPhone.text.toString().trim())
            .putString("mobile", binding.editMobile.text.toString().trim())
            .putString("email", binding.editEmail.text.toString().trim())
            .putString("website", binding.editWebsite.text.toString().trim())
            .apply()
    }

    private fun buildVCard(): String = buildString {
        val name = binding.editName.text.toString().trim()
        val company = binding.editCompany.text.toString().trim()
        val title = binding.editTitle.text.toString().trim()
        val phone = binding.editPhone.text.toString().trim()
        val mobile = binding.editMobile.text.toString().trim()
        val email = binding.editEmail.text.toString().trim()
        val website = binding.editWebsite.text.toString().trim()
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("FN:${VCardUtils.vcfEscape(name)}")
        val parts = name.trim().split(Regex("\\s+"))
        val (lastName, firstName) = when {
            CjkUtils.containsCjk(name) -> Pair(name, "")
            parts.size >= 2 -> Pair(parts.last(), parts.dropLast(1).joinToString(" "))
            else -> Pair("", name)
        }
        appendLine("N:${VCardUtils.vcfEscape(lastName)};${VCardUtils.vcfEscape(firstName)};;;")
        if (company.isNotBlank()) appendLine("ORG:${VCardUtils.vcfEscape(company)}")
        if (title.isNotBlank()) appendLine("TITLE:${VCardUtils.vcfEscape(title)}")
        if (phone.isNotBlank()) appendLine("TEL;TYPE=WORK:${VCardUtils.vcfEscape(phone)}")
        if (mobile.isNotBlank()) appendLine("TEL;TYPE=CELL:${VCardUtils.vcfEscape(mobile)}")
        if (email.isNotBlank()) appendLine("EMAIL:${VCardUtils.vcfEscape(email)}")
        if (website.isNotBlank()) appendLine("URL:${VCardUtils.vcfEscape(website)}")
        append("END:VCARD")
    }

    private suspend fun showQrCode() {
        val name = binding.editName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.my_card_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        val vcard = buildVCard()
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

        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.my_card_qr_desc)
        }

        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(imageView)
            .setPositiveButton(R.string.share) { _, _ -> shareVCard() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareVCard() {
        val name = binding.editName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, getString(R.string.my_card_name_required), Toast.LENGTH_SHORT).show()
            return
        }
        val vcard = buildVCard()
        lifecycleScope.launch {
            val file = File(cacheDir, "exports/my_card.vcf")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.writeText(vcard)
            }
            val uri = FileProvider.getUriForFile(this@MyCardActivity, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/x-vcard"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.my_card_share_chooser)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MyCardActivity, getString(R.string.no_app_for_share), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
