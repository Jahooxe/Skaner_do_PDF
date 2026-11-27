package pl.skaner.pdf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Jedna strona w trakcie skanowania: oryginalne zdjęcie (pionowo, bez kadrowania)
 * i kadr znormalizowany do 0..1 względem tego zdjęcia.
 */
data class ScanPage(val id: String, val path: String, val frame: PageFrame) {
    val file: File get() = File(path)
    /** Klucz do pamięci podręcznej podglądów – zmienia się, gdy zmieni się kadr lub plik. */
    val key: String get() = "$id|$frame|${file.lastModified()}"
}

/**
 * Bieżąca sesja skanowania (strony przed zapisem do PDF). Zapisywana na dysku,
 * więc przetrwa obrót ekranu, zamknięcie aplikacji przez system itp.
 */
object ScanSession {

    private val _pages = MutableStateFlow<List<ScanPage>>(emptyList())
    val pages: StateFlow<List<ScanPage>> = _pages
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "scan_session").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "session.json")

    fun newImageFile(context: Context): File = File(dir(context), UUID.randomUUID().toString() + ".jpg")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = indexFile(context)
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            _pages.value = List(arr.length()) {
                val o = arr.getJSONObject(it)
                ScanPage(o.getString("id"), o.getString("path"), PageFrame.fromString(o.getString("frame")))
            }.filter { it.file.exists() }
        } catch (e: Exception) {
            _pages.value = emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, page: ScanPage) = commit(context, _pages.value + page)

    @Synchronized
    fun update(context: Context, page: ScanPage) =
        commit(context, _pages.value.map { if (it.id == page.id) page else it })

    @Synchronized
    fun remove(context: Context, id: String) {
        _pages.value.find { it.id == id }?.file?.delete()
        commit(context, _pages.value.filter { it.id != id })
    }

    @Synchronized
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        _pages.value = emptyList()
    }

    fun get(id: String): ScanPage? = _pages.value.find { it.id == id }

    private fun commit(context: Context, list: List<ScanPage>) {
        _pages.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("path", it.path).put("frame", it.frame.toString())) }
        indexFile(context).writeText(arr.toString())
    }
}
