package pl.skaner.pdf

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.skaner.pdf.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScanAdapter
    private var pendingExport: File? = null

    /** Skaner ML Kit: podgląd z aparatu, auto-wykrywanie krawędzi, kadrowanie, obrót, filtry. */
    private val scanner by lazy {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .build()
        GmsDocumentScanning.getClient(options)
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val uris = scan?.pages?.map { it.imageUri }.orEmpty()
        if (uris.isNotEmpty()) previewLauncher.launch(PreviewActivity.intent(this, uris))
    }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = result.data?.getStringExtra(PreviewActivity.RESULT_PATH)
        if (result.resultCode == Activity.RESULT_OK && path != null) {
            val file = File(path)
            Snackbar.make(binding.root, getString(R.string.saved_as, file.nameWithoutExtension), Snackbar.LENGTH_LONG)
                .setAnchorView(binding.scanButton)
                .setAction(R.string.action_share) { DocumentActions.share(this, file) }
                .show()
            refresh()
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val file = pendingExport
        pendingExport = null
        if (uri == null || file == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            toast(if (ok) R.string.export_ok else R.string.export_failed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        pendingExport = savedInstanceState?.getString(KEY_EXPORT)?.let { File(it) }

        adapter = ScanAdapter(
            onOpen = { DocumentActions.open(this, it.file) },
            onPrint = { DocumentActions.print(this, it.file) },
            onEmail = { DocumentActions.email(this, it.file) },
            onShare = { DocumentActions.share(this, it.file) },
            onMore = ::showMoreMenu
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.scanButton.setOnClickListener { startScan() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExport?.let { outState.putString(KEY_EXPORT, it.absolutePath) }
    }

    private fun startScan() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.scanner_error_title)
                    .setMessage(getString(R.string.scanner_error_msg, e.localizedMessage ?: ""))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
    }

    private fun refresh() {
        lifecycleScope.launch {
            val docs = ScanRepository.list(this@MainActivity)
            adapter.submit(docs)
            binding.empty.isVisible = docs.isEmpty()
        }
    }

    private fun showMoreMenu(doc: ScanDoc, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_scan_item, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_open -> DocumentActions.open(this@MainActivity, doc.file)
                    R.id.action_export -> {
                        pendingExport = doc.file
                        exportLauncher.launch(doc.file.name)
                    }
                    R.id.action_rename -> showRenameDialog(doc)
                    R.id.action_delete -> confirmDelete(doc)
                }
                true
            }
            show()
        }
    }

    private fun showRenameDialog(doc: ScanDoc) {
        val layout = layoutInflater.inflate(R.layout.dialog_name, null) as TextInputLayout
        val input = layout.findViewById<TextInputEditText>(R.id.nameInput)
        input.setText(doc.name)
        input.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_rename)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (ScanRepository.rename(this, doc, input.text.toString()) == null) toast(R.string.rename_failed)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(doc: ScanDoc) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, doc.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                doc.file.delete()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_EXPORT = "pending_export"
    }
}
