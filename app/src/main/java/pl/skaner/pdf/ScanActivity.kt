package pl.skaner.pdf

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.skaner.pdf.databinding.ActivityScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/** Aparat: podgląd z zaznaczoną wykrytą kartką, zdjęcie → korekta kadru, import z galerii. */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var controller: LifecycleCameraController? = null
    private lateinit var analysisExecutor: ExecutorService
    private lateinit var captureExecutor: ExecutorService
    private var torchOn = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showNoPermission()
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK, result.data)
            finish()
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        setBusy(true)
        lifecycleScope.launch {
            var failed = 0
            val added = ArrayList<ScanPage>()
            withContext(Dispatchers.Default) {
                for (uri in uris) {
                    try { added += PageProcessor.fromUri(this@ScanActivity, uri) } catch (e: Throwable) { failed++ }
                }
            }
            added.forEach { ScanSession.add(this@ScanActivity, it) }
            setBusy(false)
            if (failed > 0) toast(getString(R.string.import_failed, failed))
            if (added.isNotEmpty()) openPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScanSession.load(this)
        analysisExecutor = Executors.newSingleThreadExecutor()
        captureExecutor = Executors.newSingleThreadExecutor()

        binding.closeButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.shutterButton.setOnClickListener { takePhoto() }
        binding.galleryButton.setOnClickListener {
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.doneButton.setOnClickListener { openPreview() }
        binding.torchButton.setOnClickListener {
            torchOn = !torchOn
            controller?.enableTorch(torchOn)
            binding.torchButton.alpha = if (torchOn) 1f else 0.6f
        }
        binding.torchButton.alpha = 0.6f

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val n = ScanSession.pages.value.size
                if (n == 0) { finish(); return }
                MaterialAlertDialogBuilder(this@ScanActivity)
                    .setTitle(R.string.discard_title)
                    .setMessage(resources.getQuantityString(R.plurals.discard_msg, n, n))
                    .setPositiveButton(R.string.discard) { _, _ ->
                        ScanSession.clear(this@ScanActivity)
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })

        binding.lastThumb.setOnClickListener {
            ScanSession.pages.value.lastOrNull()?.let {
                cropLauncher.launch(CropActivity.intent(this, it.id, isNew = false))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScanSession.pages.collect { pages ->
                    binding.doneButton.isVisible = pages.isNotEmpty()
                    binding.doneButton.text = getString(R.string.next_with_count, pages.size)
                    updateThumb(pages.lastOrNull())
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private fun startCamera() {
        binding.permissionText.isVisible = false
        binding.shutterButton.isEnabled = true
        val ratio43 = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val c = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            previewResolutionSelector = ratio43
            imageCaptureResolutionSelector = ratio43
            imageAnalysisResolutionSelector = ratio43
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            setImageAnalysisAnalyzer(analysisExecutor, ::analyze)
        }
        binding.previewView.controller = c
        c.bindToLifecycle(this)
        controller = c
    }

    private fun showNoPermission() {
        binding.permissionText.isVisible = true
        binding.shutterButton.isEnabled = false
    }

    /** Wykrywanie kartki na żywo na kanale jasności (Y) obrazu z kamery. */
    private fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val step = max(1, max(image.width, image.height) / 320)
            val sw = image.width / step
            val sh = image.height / step
            val lum = IntArray(sw * sh)
            for (y in 0 until sh) {
                val row = y * step * rowStride
                for (x in 0 until sw) lum[y * sw + x] = buf.get(row + x * step * pixStride).toInt() and 0xFF
            }
            var frame = PageDetector.detectLuma(lum, sw, sh)?.scaled(1f / sw, 1f / sh)
            val rotation = image.imageInfo.rotationDegrees
            repeat(rotation / 90) { frame = frame?.rotatedCw(1f) }
            val aspect = if (rotation % 180 == 0) sw.toFloat() / sh else sh.toFloat() / sw
            val result = frame
            runOnUiThread { binding.overlay.update(result, aspect) }
        } catch (e: Exception) {
            // pojedyncza nieudana klatka – pomijamy
        } finally {
            image.close()
        }
    }

    private fun takePhoto() {
        val c = controller ?: return
        setBusy(true)
        c.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val page = try {
                    val rotation = image.imageInfo.rotationDegrees
                    val bmp = image.toBitmap()
                    image.close()
                    PageProcessor.fromCamera(this@ScanActivity, bmp, rotation)
                } catch (e: Throwable) {
                    image.close()
                    null
                }
                runOnUiThread {
                    setBusy(false)
                    if (page == null) {
                        toast(getString(R.string.capture_failed))
                    } else {
                        // w pełni automatycznie: bez ekranu korekty; miniatura pozwala poprawić w razie potrzeby
                        ScanSession.add(this@ScanActivity, page)
                        flash()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    setBusy(false)
                    toast(getString(R.string.capture_failed))
                }
            }
        })
    }

    private var thumbKey: String? = null

    /** Miniatura ostatniej strony (już wykadrowanej i wyprostowanej) z ewentualnym ostrzeżeniem. */
    private fun updateThumb(page: ScanPage?) {
        binding.thumbBox.isVisible = page != null
        binding.reviewBadge.isVisible = page?.needsReview == true
        if (page == null || page.key == thumbKey) return
        thumbKey = page.key
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                try { PageProcessor.render(page, 320, EnhanceMode.ORIGINAL, false) } catch (e: Throwable) { null }
            }
            if (thumbKey == page.key) binding.lastThumb.setImageBitmap(bmp)
        }
    }

    /** Krótki błysk po zrobieniu zdjęcia. */
    private fun flash() {
        binding.flashView.alpha = 0.8f
        binding.flashView.isVisible = true
        binding.flashView.animate().alpha(0f).setDuration(250).withEndAction { binding.flashView.isVisible = false }.start()
    }

    private fun openPreview() {
        if (ScanSession.pages.value.isEmpty()) return
        previewLauncher.launch(Intent(this, PreviewActivity::class.java))
    }

    private fun setBusy(busy: Boolean) {
        binding.busy.isVisible = busy
        binding.shutterButton.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        fun intent(context: Context) = Intent(context, ScanActivity::class.java)
    }
}
