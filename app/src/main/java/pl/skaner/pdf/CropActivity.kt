package pl.skaner.pdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.skaner.pdf.databinding.ActivityCropBinding

/** Korekta kadru jednej strony: rogi, wygięcie krawędzi, ponowne wykrycie, cała strona, obrót. */
class CropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropBinding
    private lateinit var page: ScanPage
    private var bitmap: Bitmap? = null
    private var isNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ScanSession.load(this)
        page = ScanSession.get(intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        isNew = intent.getBooleanExtra(EXTRA_NEW, false)

        binding.cancelButton.setText(if (isNew) R.string.retake else android.R.string.cancel)
        binding.cancelButton.setOnClickListener {
            if (isNew) ScanSession.remove(this, page.id)
            finish()
        }
        binding.acceptButton.setOnClickListener { accept() }
        binding.detectButton.setOnClickListener { redetect() }
        binding.fullButton.setOnClickListener {
            val b = bitmap ?: return@setOnClickListener
            binding.cropView.setFrame(PageFrame.full(b.width.toFloat(), b.height.toFloat()))
        }
        binding.rotateButton.setOnClickListener { rotate() }

        val restored = savedInstanceState?.getString(KEY_FRAME)?.let { PageFrame.fromString(it) }
        load(restored ?: page.frame)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentNormalized()?.let { outState.putString(KEY_FRAME, it.toString()) }
    }

    private fun load(normalized: PageFrame) {
        setBusy(true)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { PageProcessor.decodeFile(page.file, DISPLAY_SIZE) }
            bitmap = bmp
            binding.cropView.setImage(bmp, normalized.scaled(bmp.width.toFloat(), bmp.height.toFloat()))
            setBusy(false)
        }
    }

    private fun currentNormalized(): PageFrame? {
        val b = bitmap ?: return null
        return binding.cropView.frame?.scaled(1f / b.width, 1f / b.height)
    }

    private fun redetect() {
        val b = bitmap ?: return
        setBusy(true)
        lifecycleScope.launch {
            val f = withContext(Dispatchers.Default) { PageProcessor.detect(b) }
            binding.cropView.setFrame(f.scaled(b.width.toFloat(), b.height.toFloat()))
            setBusy(false)
        }
    }

    private fun rotate() {
        val f = currentNormalized() ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                page = withContext(Dispatchers.Default) { PageProcessor.rotate(page.copy(frame = f)) }
                ScanSession.update(this@CropActivity, page)
                load(page.frame)
            } catch (e: Throwable) {
                setBusy(false)
                Toast.makeText(this@CropActivity, R.string.rotate_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun accept() {
        val f = currentNormalized() ?: return
        ScanSession.update(this, page.copy(frame = f))
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun setBusy(busy: Boolean) {
        binding.busy.isVisible = busy
        binding.acceptButton.isEnabled = !busy
    }

    companion object {
        private const val EXTRA_ID = "page_id"
        private const val EXTRA_NEW = "is_new"
        private const val KEY_FRAME = "frame"
        private const val DISPLAY_SIZE = 2000

        fun intent(context: Context, pageId: String, isNew: Boolean): Intent =
            Intent(context, CropActivity::class.java).putExtra(EXTRA_ID, pageId).putExtra(EXTRA_NEW, isNew)
    }
}
