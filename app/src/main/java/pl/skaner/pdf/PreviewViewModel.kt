package pl.skaner.pdf

import android.app.Application
import android.graphics.Bitmap
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

/** Pozycja podglądu: strona i (gdy gotowy) jej przetworzony obraz. */
data class PreviewItem(val page: ScanPage, val bitmap: Bitmap?)

class PreviewViewModel(app: Application) : AndroidViewModel(app) {

    val mode = MutableStateFlow(EnhanceMode.COLOR)
    val sharpen = MutableStateFlow(true)

    private val _items = MutableStateFlow<List<PreviewItem>>(emptyList())
    val items: StateFlow<List<PreviewItem>> = _items

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Postęp zapisu: liczba gotowych stron albo null, gdy nic nie jest zapisywane. */
    private val _saveProgress = MutableStateFlow<Int?>(null)
    val saveProgress: StateFlow<Int?> = _saveProgress

    private val _saved = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val saved: SharedFlow<File> = _saved

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error

    /** Pamięć podręczna podglądów: klucz strony + tryb → obraz. */
    private val cache = HashMap<String, Bitmap>()

    init {
        ScanSession.load(app)
        viewModelScope.launch {
            combine(ScanSession.pages, mode, sharpen) { p, m, s -> Triple(p, m, s) }.collectLatest { (pages, m, s) ->
                val suffix = "|$m|$s"
                // od razu pokaż to, co już jest policzone
                _items.value = pages.map { PreviewItem(it, cache[it.key + suffix]) }
                _busy.value = true
                for (page in pages) {
                    val k = page.key + suffix
                    if (cache[k] != null) continue
                    val bmp = try {
                        withContext(Dispatchers.Default) {
                            ensureActive()
                            PageProcessor.render(page, PageProcessor.PREVIEW_SIZE, m, s)
                        }
                    } catch (e: OutOfMemoryError) { null }
                    if (bmp != null) cache[k] = bmp
                    _items.value = pages.map { PreviewItem(it, cache[it.key + suffix]) }
                }
                // usuń z pamięci podglądy nieużywanych wersji
                val keep = pages.map { it.key + suffix }.toSet()
                cache.keys.retainAll(keep)
                _busy.value = false
            }
        }
    }

    fun rotate(page: ScanPage) {
        viewModelScope.launch {
            try {
                val rotated = withContext(Dispatchers.Default) { PageProcessor.rotate(page) }
                ScanSession.update(getApplication(), rotated)
            } catch (e: Throwable) {
                _error.emit(e.localizedMessage ?: e.toString())
            }
        }
    }

    fun delete(page: ScanPage) = ScanSession.remove(getApplication(), page.id)

    fun save(name: String) {
        val pages = ScanSession.pages.value
        if (_saveProgress.value != null || pages.isEmpty()) return
        val m = mode.value
        val s = sharpen.value
        _saveProgress.value = 0
        viewModelScope.launch {
            val out = ScanRepository.newFile(getApplication(), name)
            try {
                withContext(Dispatchers.Default) {
                    PdfWriter(out).use { pdf ->
                        pages.forEachIndexed { i, page ->
                            val img = PageProcessor.render(page, PageProcessor.FULL_SIZE, m, s)
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
                ScanSession.clear(getApplication())
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
