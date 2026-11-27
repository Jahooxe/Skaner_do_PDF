package pl.skaner.pdf

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

/**
 * Podgląd zeskanowanych stron: tryb poprawy, wyostrzanie, kadrowanie/obrót/usuwanie stron,
 * dodawanie kolejnych stron i zapis do PDF.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private val vm: PreviewViewModel by viewModels()
    private lateinit var pageAdapter: PageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) binding.nameInput.setText(ScanRepository.defaultName())
        pageAdapter = PageAdapter(
            onCrop = { startActivity(CropActivity.intent(this, it.id, isNew = false)) },
            onRotate = { vm.rotate(it) },
            onDelete = { page ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.delete_page_title)
                    .setPositiveButton(R.string.action_delete) { _, _ -> vm.delete(page) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        )
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
        binding.addButton.setOnClickListener { finish() } // powrót do aparatu

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.items.collect { items ->
                        pageAdapter.submit(items)
                        if (ScanSession.pages.value.isEmpty() && vm.saveProgress.value == null) finish()
                    }
                }
                launch { vm.busy.collect { binding.busy.isVisible = it } }
                launch {
                    vm.saveProgress.collect { done ->
                        val saving = done != null
                        val total = ScanSession.pages.value.size
                        binding.saveProgress.isVisible = saving
                        binding.saveButton.isEnabled = !saving
                        binding.addButton.isEnabled = !saving
                        binding.modeGroup.children.forEach { it.isEnabled = !saving }
                        binding.sharpenSwitch.isEnabled = !saving
                        if (done != null) {
                            binding.saveProgress.max = maxOf(1, total)
                            binding.saveProgress.setProgressCompat(done, true)
                            binding.saveButton.text = getString(R.string.saving_page, done, total)
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

    private class PageAdapter(
        private val onCrop: (ScanPage) -> Unit,
        private val onRotate: (ScanPage) -> Unit,
        private val onDelete: (ScanPage) -> Unit
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {
        private var items: List<PreviewItem> = emptyList()

        fun submit(list: List<PreviewItem>) {
            items = list
            notifyDataSetChanged()
        }

        class Holder(val b: ItemPageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            with(holder.b) {
                if (item.bitmap != null) {
                    pageImage.setImageBitmap(item.bitmap)
                    pageImage.minimumHeight = 0
                    pageLoading.isVisible = false
                } else {
                    pageImage.setImageDrawable(null)
                    pageImage.minimumHeight = (300 * root.resources.displayMetrics.density).toInt()
                    pageLoading.isVisible = true
                }
                pageNumber.text = root.context.getString(R.string.page_n, position + 1, items.size)
                reviewLabel.isVisible = item.page.needsReview
                reviewLabel.setOnClickListener { onCrop(item.page) }
                cropButton.setOnClickListener { onCrop(item.page) }
                rotateButton.setOnClickListener { onRotate(item.page) }
                deleteButton.setOnClickListener { onDelete(item.page) }
            }
        }
    }

    companion object {
        const val RESULT_PATH = "result_path"
    }
}
