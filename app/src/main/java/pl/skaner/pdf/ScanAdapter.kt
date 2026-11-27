package pl.skaner.pdf

import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import pl.skaner.pdf.databinding.ItemScanBinding

class ScanAdapter(
    private val onOpen: (ScanDoc) -> Unit,
    private val onPrint: (ScanDoc) -> Unit,
    private val onEmail: (ScanDoc) -> Unit,
    private val onShare: (ScanDoc) -> Unit,
    private val onMore: (ScanDoc, View) -> Unit
) : RecyclerView.Adapter<ScanAdapter.Holder>() {

    private var items: List<ScanDoc> = emptyList()

    fun submit(list: List<ScanDoc>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(val b: ItemScanBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemScanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val doc = items[position]
        val ctx = holder.itemView.context
        with(holder.b) {
            title.text = doc.name
            val date = DateUtils.formatDateTime(
                ctx, doc.modified,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE
            )
            val pages = ctx.resources.getQuantityString(R.plurals.pages, doc.pages, doc.pages)
            subtitle.text = "$date · $pages · ${Formatter.formatShortFileSize(ctx, doc.size)}"
            if (doc.thumbnail != null) thumbnail.setImageBitmap(doc.thumbnail)
            else thumbnail.setImageResource(R.drawable.ic_document)

            root.setOnClickListener { onOpen(doc) }
            printButton.setOnClickListener { onPrint(doc) }
            emailButton.setOnClickListener { onEmail(doc) }
            shareButton.setOnClickListener { onShare(doc) }
            moreButton.setOnClickListener { onMore(doc, it) }
        }
    }
}
