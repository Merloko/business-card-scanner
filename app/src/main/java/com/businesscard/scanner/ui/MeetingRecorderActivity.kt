package com.businesscard.scanner.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.businesscard.scanner.R
import com.businesscard.scanner.data.InteractionLog
import com.businesscard.scanner.databinding.ActivityMeetingRecorderBinding

class MeetingRecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeetingRecorderBinding
    private val viewModel: BusinessCardViewModel by viewModels()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private val transcript = StringBuilder()
    private val keyActions = mutableListOf<String>()
    private var cardId: Long = -1L

    private val sentenceSplitPattern = Regex("[.!?\n]+")

    private val actionVerbRegex = Regex(
        "\\b(will|need to|should|must|going to|follow[- ]?up|action|call|send|email|meet|" +
        "schedule|book|arrange|confirm|check|review|prepare|finish|complete|deliver|provide|" +
        "update|discuss|contact|invite|submit|report|get back|let you know|reach out)\\b",
        RegexOption.IGNORE_CASE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeetingRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.meeting_recorder_title)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1L)

        binding.btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        binding.btnSave.setOnClickListener { saveToContact() }
        updateUI()
    }

    private fun startRecording() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.speech_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        isRecording = true
        updateUI()
        listenOnce()
    }

    private fun listenOnce() {
        if (!isRecording) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    binding.textTranscript.text = buildString {
                        if (transcript.isNotEmpty()) append(transcript); append(" ")
                        append(partial)
                        append("…")
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val words = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!words.isNullOrBlank()) {
                    if (transcript.isNotEmpty()) transcript.append(" ")
                    transcript.append(words)
                    extractActions(words)
                    updateTranscriptUI()
                }
                if (isRecording) listenOnce()
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (isRecording) listenOnce()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        binding.root.postDelayed({ if (isRecording) listenOnce() }, 500)
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        stopRecording()
                        Toast.makeText(
                            this@MeetingRecorderActivity,
                            getString(R.string.meeting_recording_error, error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else ->
                        binding.root.postDelayed({ if (isRecording) listenOnce() }, 1000)
                }
            }
        })
        speechRecognizer?.startListening(intent)
    }

    private fun stopRecording() {
        isRecording = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        updateUI()
    }

    private fun extractActions(text: String) {
        text.split(sentenceSplitPattern)
            .map { it.trim() }
            .filter { it.length > 10 && actionVerbRegex.containsMatchIn(it) && it !in keyActions }
            .forEach { keyActions.add(it) }
        updateActionsUI()
    }

    private fun updateTranscriptUI() {
        val text = transcript.toString()
        binding.textTranscript.text = text.ifBlank { getString(R.string.meeting_transcript_empty) }
        binding.transcriptScroll.post { binding.transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateActionsUI() {
        binding.actionsContainer.removeAllViews()
        if (keyActions.isEmpty()) {
            binding.textNoActions.visibility = View.VISIBLE
        } else {
            binding.textNoActions.visibility = View.GONE
            val pad = (6 * resources.displayMetrics.density).toInt()
            keyActions.forEach { action ->
                val tv = TextView(this).apply {
                    text = "• $action"
                    textSize = 13f
                    setPadding(0, pad, 0, pad)
                }
                binding.actionsContainer.addView(tv)
            }
        }
    }

    private fun updateUI() {
        if (isRecording) {
            binding.btnRecord.text = getString(R.string.meeting_stop)
            binding.textStatus.text = getString(R.string.meeting_recording)
            binding.recordingIndicator.setBackgroundResource(R.drawable.bg_recording_active)
        } else {
            binding.btnRecord.text = getString(R.string.meeting_start)
            binding.textStatus.text =
                if (transcript.isNotEmpty()) getString(R.string.meeting_stopped)
                else getString(R.string.meeting_idle)
            binding.recordingIndicator.setBackgroundResource(R.drawable.bg_recording_indicator)
        }
        binding.btnSave.isEnabled = transcript.isNotEmpty() && !isRecording
    }

    private fun saveToContact() {
        val summary = buildString {
            appendLine(transcript.toString())
            if (keyActions.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.meeting_key_actions) + ":")
                keyActions.forEach { appendLine("• $it") }
            }
        }.trim()

        if (cardId != -1L) {
            viewModel.insertLog(InteractionLog(cardId = cardId, type = "Meeting", note = summary))
            Toast.makeText(this, getString(R.string.meeting_saved), Toast.LENGTH_SHORT).show()
            finish()
        } else {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, summary)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.meeting_share_chooser)))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }
}
