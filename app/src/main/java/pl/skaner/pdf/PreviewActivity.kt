package pl.skaner.pdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import pl.skaner.pdf.databinding.ActivityPreviewBinding
import pl.skaner.pdf.databinding.ItemPageBinding

/** Podgląd zeskanowanych stron: wybór trybu poprawy, wyostrzanie, nazwa pliku i zapis do PDF. */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val vm: PreviewViewModel by viewModels()
    private val pageAdapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_PAGES, Uri::class.java).orEmpty()
        if (uris.isEmpty()) { finish(); return }
        vm.init(uris)

        if (savedInstanceState == null) binding.nameInput.setText(ScanRepository.defaultName())
        binding.pageList.layoutManager = LinearLayoutManager(this)
        binding.pageList.adapter = pageAdapter

        binding.modeGroup.check(chipFor(vm.mode.value))
        binding.modeGroup.setOnCheckedStateChangeListener { _, ids ->
            vm.mode.value = when (ids.firstOrNull()) {
                R.id.chipOriginal -> EnhanceMode.ORIGINAL
                R.id.chipGray -> EnhanceMode.GRAY
                R.id.chipBw -> EnhanceMode.BLACK_WHITE
                else -> EnhanceMode.COLOR
            }
        }
        binding.sharpenSwitch.isChecked = vm.sharpen.value
        binding.sharpenSwitch.setOnCheckedChangeListener { _, checked -> vm.sharpen.value = checked }
        binding.saveButton.setOnClickListener { vm.save(binding.nameInput.text?.toString().orEmpty()) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.previews.collect { pageAdapter.submit(it) } }
                launch { vm.busy.collect { binding.busy.isVisible = it } }
                launch {
                    vm.saveProgress.collect { done ->
                        val saving = done != null
                        binding.saveProgress.isVisible = saving
                        binding.saveButton.isEnabled = !saving
                        binding.modeGroup.children.forEach { it.isEnabled = !saving }
                        binding.sharpenSwitch.isEnabled = !saving
                        if (saving) {
                            binding.saveProgress.max = vm.pages.size
                            binding.saveProgress.setProgressCompat(done!!, true)
                            binding.saveButton.text = getString(R.string.saving_page, done, vm.pages.size)
                        } else {
                            binding.saveButton.setText(R.string.save_pdf)
                        }
                    }
                }
                launch {
                    vm.saved.collect { file ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PATH, file.absolutePath))
                        finish()
                    }
                }
                launch {
                    vm.error.collect { msg ->
                        MaterialAlertDialogBuilder(this@PreviewActivity)
                            .setTitle(R.string.error_title)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun chipFor(mode: EnhanceMode) = when (mode) {
        EnhanceMode.ORIGINAL -> R.id.chipOriginal
        EnhanceMode.COLOR -> R.id.chipColor
        EnhanceMode.GRAY -> R.id.chipGray
        EnhanceMode.BLACK_WHITE -> R.id.chipBw
    }

    private class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        private var items: List<Bitmap> = emptyList()

        fun submit(list: List<Bitmap>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.b.pageImage.setImageBitmap(items[position])
            holder.b.pageNumber.text = holder.itemView.context.getString(R.string.page_n, position + 1, items.size)
        }
    }

    companion object {
        private const val EXTRA_PAGES = "pages"
        const val RESULT_PATH = "result_path"

        fun intent(context: Context, pages: List<Uri>): Intent =
            Intent(context, PreviewActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_PAGES, ArrayList(pages))
    }
}
