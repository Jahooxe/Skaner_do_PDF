package pl.skaner.pdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    val mode = MutableStateFlow(EnhanceMode.COLOR)
    val sharpen = MutableStateFlow(true)

    private val _previews = MutableStateFlow<List<Bitmap>>(emptyList())
    val previews: StateFlow<List<Bitmap>> = _previews

    private val _busy = MutableStateFlow(true)
    val busy: StateFlow<Boolean> = _busy

    /** Postęp zapisu: liczba gotowych stron albo null, gdy nic nie jest zapisywane. */
    private val _saveProgress = MutableStateFlow<Int?>(null)
    val saveProgress: StateFlow<Int?> = _saveProgress

    private val _saved = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val saved: SharedFlow<File> = _saved

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error

    var pages: List<Uri> = emptyList()
        private set
    private var sources: List<Bitmap> = emptyList()

    fun init(uris: List<Uri>) {
        if (pages.isNotEmpty()) return
        pages = uris
        viewModelScope.launch {
            try {
                sources = withContext(Dispatchers.IO) {
                    uris.map { ImageEnhancer.decode(getApplication(), it, ImageEnhancer.PREVIEW_SIZE) }
                }
            } catch (e: Exception) {
                _error.emit(e.localizedMessage ?: e.toString())
                return@launch
            }
            // przelicz podgląd przy każdej zmianie trybu lub wyostrzania
            combine(mode, sharpen) { m, s -> m to s }.collectLatest { (m, s) ->
                _busy.value = true
                val result = withContext(Dispatchers.Default) {
                    sources.map { src ->
                        ensureActive()
                        ImageEnhancer.enhance(src, m, s)
                    }
                }
                _previews.value = result
                _busy.value = false
            }
        }
    }

    fun save(name: String) {
        if (_saveProgress.value != null || pages.isEmpty()) return
        val m = mode.value
        val s = sharpen.value
        _saveProgress.value = 0
        viewModelScope.launch {
            val out = ScanRepository.newFile(getApplication(), name)
            try {
                withContext(Dispatchers.Default) {
                    PdfWriter(out).use { pdf ->
                        pages.forEachIndexed { i, uri ->
                            val src = ImageEnhancer.decode(getApplication(), uri, ImageEnhancer.FULL_SIZE)
                            val img = ImageEnhancer.enhance(src, m, s)
                            src.recycle()
                            val jpeg = ByteArrayOutputStream().use { bos ->
                                img.compress(Bitmap.CompressFormat.JPEG, if (m == EnhanceMode.BLACK_WHITE) 90 else 85, bos)
                                bos.toByteArray()
                            }
                            pdf.addJpegPage(jpeg, img.width, img.height)
                            img.recycle()
                            _saveProgress.value = i + 1
                        }
                    }
                }
                _saved.emit(out)
            } catch (e: Throwable) {
                out.delete()
                val msg = if (e is OutOfMemoryError) "Za mało pamięci do przetworzenia strony." else (e.localizedMessage ?: e.toString())
                _error.emit(msg)
            } finally {
                _saveProgress.value = null
            }
        }
    }
}
