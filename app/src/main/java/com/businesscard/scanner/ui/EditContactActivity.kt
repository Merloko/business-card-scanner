package com.businesscard.scanner.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.databinding.ActivityEditContactBinding
import com.businesscard.scanner.ocr.OcrHelper
import com.businesscard.scanner.ocr.TextParser
import kotlinx.coroutines.launch

class EditContactActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditContactBinding
    private val viewModel: BusinessCardViewModel by viewModels()
    private val ocrHelper = OcrHelper()

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!words.isNullOrBlank()) {
                val current = binding.editNotes.text.toString()
                val updated = if (current.isBlank()) words else "$current\n$words"
                binding.editNotes.setText(updated)
                binding.editNotes.setSelection(updated.length)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditContactBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.edit_contact)

        val cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)
        if (cardId == -1L) { finish(); return }

        lifecycleScope.launch load@{
            val card = viewModel.getCardById(cardId)
            if (card == null) { finish(); return@load }

            binding.editPersonName.setText(card.personName)
            binding.editCompanyName.setText(card.companyName)
            binding.editJobTitle.setText(card.jobTitle)
            binding.editPhone.setText(card.phone)
            binding.editMobile.setText(card.mobile)
            binding.editEmail.setText(card.email)
            binding.editWebsite.setText(card.website)
            binding.editAddress.setText(card.address)
            binding.editNotes.setText(card.notes)
            setupTagChips(card)

            // Hide rescan button if no images were stored
            val hasImages = card.frontImagePath.isNotBlank() || card.backImagePath.isNotBlank()
            binding.btnRescan.visibility = if (hasImages) View.VISIBLE else View.GONE

            binding.btnRescan.setOnClickListener {
                lifecycleScope.launch {
                    setBusy(true)
                    try {
                        val frontText = ocrHelper.recognizeFile(this@EditContactActivity, card.frontImagePath)
                        val backText = ocrHelper.recognizeFile(this@EditContactActivity, card.backImagePath)

                        if (frontText.isBlank() && backText.isBlank()) {
                            Toast.makeText(this@EditContactActivity,
                                getString(R.string.rescan_no_images), Toast.LENGTH_LONG).show()
                            return@launch
                        }

                        val parsed = TextParser.parse(frontText, backText)
                        // Overwrite parsed fields; leave notes untouched (user-written)
                        if (parsed.personName.isNotBlank())  binding.editPersonName.setText(parsed.personName)
                        if (parsed.companyName.isNotBlank()) binding.editCompanyName.setText(parsed.companyName)
                        if (parsed.jobTitle.isNotBlank())    binding.editJobTitle.setText(parsed.jobTitle)
                        if (parsed.phone.isNotBlank())       binding.editPhone.setText(parsed.phone)
                        if (parsed.mobile.isNotBlank())      binding.editMobile.setText(parsed.mobile)
                        if (parsed.email.isNotBlank())       binding.editEmail.setText(parsed.email)
                        if (parsed.website.isNotBlank())     binding.editWebsite.setText(parsed.website)
                        if (parsed.address.isNotBlank())     binding.editAddress.setText(parsed.address)

                        Toast.makeText(this@EditContactActivity, getString(R.string.rescan_success), Toast.LENGTH_SHORT).show()
                    } finally {
                        setBusy(false)
                    }
                }
            }

            binding.btnDictateNotes.setOnClickListener { startSpeechRecognition() }

            binding.btnSave.setOnClickListener {
                val updated = card.copy(
                    personName  = binding.editPersonName.text.toString().trim(),
                    companyName = binding.editCompanyName.text.toString().trim(),
                    jobTitle    = binding.editJobTitle.text.toString().trim(),
                    phone       = binding.editPhone.text.toString().trim(),
                    mobile      = binding.editMobile.text.toString().trim(),
                    email       = binding.editEmail.text.toString().trim(),
                    website     = binding.editWebsite.text.toString().trim(),
                    address     = binding.editAddress.text.toString().trim(),
                    notes       = binding.editNotes.text.toString().trim(),
                    tags        = binding.editTags.text.toString().trim()
                )
                lifecycleScope.launch {
                    viewModel.updateNow(updated)
                    Toast.makeText(this@EditContactActivity, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.rescanProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnRescan.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
    }

    private fun setupTagChips(card: BusinessCard) {
        renderTagChips(card.tags)
        binding.chipAddTag.setOnClickListener {
            val input = android.widget.EditText(this).apply { hint = getString(R.string.tag_name_hint) }
            AlertDialog.Builder(this)
                .setTitle(R.string.add_tag_title)
                .setView(input)
                .setPositiveButton(R.string.add) { _, _ ->
                    val newTag = input.text.toString().trim()
                    if (newTag.isNotBlank()) {
                        val current = binding.editTags.text.toString()
                        val updated = if (current.isBlank()) newTag else "$current,$newTag"
                        binding.editTags.setText(updated)
                        renderTagChips(updated)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun renderTagChips(tagsStr: String) {
        val addChip = binding.chipAddTag
        binding.chipGroupTags.removeAllViews()
        binding.chipGroupTags.addView(addChip)
        tagsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = tag
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    val tags = binding.editTags.text.toString()
                        .split(",").map { it.trim() }.filter { it.isNotBlank() && it != tag }
                    val updated = tags.joinToString(",")
                    binding.editTags.setText(updated)
                    renderTagChips(updated)
                }
            }
            binding.chipGroupTags.addView(chip, binding.chipGroupTags.childCount - 1)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHelper.close()
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.speech_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }
}
