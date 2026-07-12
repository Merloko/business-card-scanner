package com.businesscard.scanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.businesscard.scanner.BuildConfig
import com.businesscard.scanner.R
import com.businesscard.scanner.data.BusinessCard
import com.businesscard.scanner.databinding.ActivityScanBinding
import com.businesscard.scanner.ocr.OcrHelper
import com.businesscard.scanner.ocr.OcrLine
import com.businesscard.scanner.ocr.TextParser
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.IOException

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val viewModel: BusinessCardViewModel by viewModels()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var scanMessageJob: Job? = null

    private var frontImagePath = ""
    private var backImagePath = ""
    private var frontLines: List<com.businesscard.scanner.ocr.OcrLine> = emptyList()
    private var backLines:  List<com.businesscard.scanner.ocr.OcrLine> = emptyList()
    private var frontText = ""   // plain text kept for rawTextFront DB field and OCR preview
    private var backText  = ""   // plain text kept for rawTextBack DB field and OCR preview
    private var isScanningBack = false
    private var eventTag = ""

    private val ocrHelper = OcrHelper()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { runOcrFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }

        binding.btnCapture.setOnClickListener { captureImage() }
        binding.btnGallery.setOnClickListener { pickImage.launch("image/*") }
        binding.btnFlip.setOnClickListener { flipToBack() }
        binding.btnSaveContact.setOnClickListener { saveContact() }
        binding.btnPhotoScan.setOnClickListener { openPhotoScan() }
        updateUI()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scan, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_event_tag) { showEventTagDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showEventTagDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.event_name_hint)
            setText(eventTag)
            selectAll()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.event_mode_title))
            .setView(input)
            .setPositiveButton(R.string.set) { _, _ ->
                eventTag = input.text.toString().trim()
                updateEventUI()
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                eventTag = ""
                updateEventUI()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateEventUI() {
        supportActionBar?.subtitle = if (eventTag.isNotBlank())
            getString(R.string.event_active_subtitle, eventTag)
        else null
    }

    private fun openPhotoScan() {
        val packages = listOf(
            "com.google.android.apps.photos.scanner",
            "com.google.android.apps.photoscan"
        )
        for (pkg in packages) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                startActivity(launch)
                return
            }
        }
        // Not installed — open Play Store listing
        try {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.photos.scanner")))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.photos.scanner")))
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        val ic = imageCapture ?: return
        setBusy(true)
        val cam = camera
        val w = binding.viewFinder.width.toFloat()
        val h = binding.viewFinder.height.toFloat()
        if (cam != null && w > 0 && h > 0) {
            val point = binding.viewFinder.meteringPointFactory.createPoint(w / 2f, h / 2f)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .disableAutoCancel()
                .build()
            cam.cameraControl.startFocusAndMetering(action).addListener(
                { doCapture(ic) },
                ContextCompat.getMainExecutor(this)
            )
        } else {
            doCapture(ic)
        }
    }

    private fun doCapture(ic: ImageCapture) {
        val fileName = "card_${if (isScanningBack) "back" else "front"}_${System.currentTimeMillis()}.jpg"
        val file = File(filesDir, fileName)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch { runOcr(file.absolutePath) }
                }
                override fun onError(exc: ImageCaptureException) {
                    setBusy(false)
                    showScanMessage(getString(R.string.capture_failed, exc.message), long = true)
                }
            }
        )
    }

    private fun showScanMessage(msg: String, long: Boolean = false) {
        scanMessageJob?.cancel()
        binding.textScanMessage.text = msg
        binding.scanMessageCard.alpha = 1f
        binding.scanMessageCard.visibility = View.VISIBLE
        scanMessageJob = lifecycleScope.launch {
            delay(if (long) 3500L else 2000L)
            binding.scanMessageCard.animate()
                .alpha(0f).setDuration(400)
                .withEndAction { binding.scanMessageCard.visibility = View.GONE }
                .start()
        }
    }

    private fun runOcrFromUri(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val fileName = "card_${if (isScanningBack) "back" else "front"}_${System.currentTimeMillis()}.jpg"
                val destFile = File(filesDir, fileName)
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open image stream")
                    stream.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                runOcr(destFile.absolutePath)
            } catch (e: Exception) {
                setBusy(false)
                showScanMessage("Could not read image: ${e.message}", long = true)
            }
        }
    }

    private suspend fun runOcr(imagePath: String) {
        val image = try {
            withContext(Dispatchers.IO) {
                InputImage.fromFilePath(this@ScanActivity, Uri.fromFile(File(imagePath)))
            }
        } catch (e: Exception) {
            setBusy(false)
            showScanMessage(getString(R.string.image_load_error, e.message), long = true)
            return
        }
        val lines = ocrHelper.recognize(image)
        val text = lines.joinToString("\n") { it.text }
        setBusy(false)
        if (text.isBlank()) {
            showScanMessage(getString(R.string.ocr_no_text), long = true)
            return
        }
        if (isScanningBack) {
            replacePreviousImage(backImagePath, imagePath)
            backImagePath = imagePath
            backLines = lines
            backText  = text
            binding.textOcrResult.text = "Back:\n$text"
            binding.rowSecondary.visibility = View.VISIBLE
            binding.btnFlip.visibility = View.GONE
            binding.btnSaveContact.visibility = View.VISIBLE
            binding.btnCapture.visibility = View.GONE
            binding.btnGallery.visibility = View.GONE
            showScanMessage(getString(R.string.back_scanned))
        } else {
            replacePreviousImage(frontImagePath, imagePath)
            frontImagePath = imagePath
            frontLines = lines
            frontText  = text
            binding.textOcrResult.text = "Front:\n$text"
            binding.rowSecondary.visibility = View.VISIBLE
            binding.btnFlip.visibility = View.VISIBLE
            binding.btnSaveContact.visibility = View.VISIBLE
            showScanMessage(getString(R.string.front_scanned))
        }
        updateUI()
    }

    // A retake before saving overwrites frontImagePath/backImagePath, otherwise
    // orphaning the previous capture's file on disk with nothing left to reference it.
    private fun replacePreviousImage(oldPath: String, newPath: String) {
        if (oldPath.isNotBlank() && oldPath != newPath) File(oldPath).delete()
    }

    private fun flipToBack() {
        isScanningBack = true
        binding.textOcrResult.text = ""
        binding.rowSecondary.visibility = View.GONE
        updateUI()
        showScanMessage(getString(R.string.flip_card))
    }

    private fun saveContact() {
        val parsed = TextParser.parse(frontLines, backLines)
        val initialTags = eventTag.ifBlank { "" }
        val card = BusinessCard(
            personName = parsed.personName,
            companyName = parsed.companyName,
            jobTitle = parsed.jobTitle,
            phone = parsed.phone,
            mobile = parsed.mobile,
            email = parsed.email,
            website = parsed.website,
            address = parsed.address,
            rawTextFront = frontText,
            rawTextBack = backText,
            frontImagePath = frontImagePath,
            backImagePath = backImagePath,
            tags = initialTags
        )
        lifecycleScope.launch {
            val duplicate = viewModel.findDuplicate(parsed.personName, parsed.email)
            if (duplicate != null && (parsed.personName.isNotBlank() || parsed.email.isNotBlank())) {
                AlertDialog.Builder(this@ScanActivity)
                    .setTitle(R.string.possible_duplicate_title)
                    .setMessage(getString(R.string.possible_duplicate_message, duplicate.personName))
                    .setPositiveButton(R.string.save_anyway) { _, _ ->
                        viewModel.insert(card) {
                            showScanMessage(getString(R.string.contact_saved))
                            finish()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                viewModel.insert(card) {
                    showScanMessage(getString(R.string.contact_saved))
                    finish()
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnCapture.isEnabled = !busy
        binding.btnGallery.isEnabled = !busy
    }

    private fun updateUI() {
        binding.textScanInstruction.text = if (isScanningBack)
            getString(R.string.scan_back_instruction)
        else
            getString(R.string.scan_front_instruction)
        supportActionBar?.title = if (isScanningBack)
            getString(R.string.scan_back_title)
        else
            getString(R.string.scan_front_title)
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrHelper.close()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val TAG = "ScanActivity"
    }
}
