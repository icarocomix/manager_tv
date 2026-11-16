package com.icarocomix.icaromanagertv.common.ui

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icarocomix.icaromanagertv.common.R
import com.icarocomix.icaromanagertv.common.protocol.Entry
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.protocol.Listing
import com.icarocomix.icaromanagertv.common.protocol.ListingProgress
import java.util.Date

/**
 * Adattatore dell'elenco condiviso da TV e telefono. Mostra solo le voci visibili (filtrate dalla
 * ricerca) ma lavora sempre con gli indici dell'elenco completo, gli stessi che viaggiano nel
 * protocollo: chi lo usa non deve mai convertire posizioni.
 *
 * [focusable]: true sulla TV, dove le righe ricevono il focus del D-pad.
 */
class EntryAdapter(
    private val focusable: Boolean,
    private val onOpen: (index: Int) -> Unit,
    private val onSelect: (index: Int) -> Unit,
) : RecyclerView.Adapter<EntryAdapter.Holder>() {

    private var listing: Listing = Listing.EMPTY
    private var visible: IntArray = IntArray(0)
    private var selected: Int = -1
    private var progress: ListingProgress = ListingProgress.EMPTY

    init {
        setHasStableIds(true)
    }

    /** Indice nell'elenco completo della riga in posizione [position]. */
    fun indexAt(position: Int): Int = visible.getOrElse(position) { -1 }

    /** Posizione a schermo dell'indice [index] dell'elenco completo, -1 se filtrato. */
    fun positionOf(index: Int): Int = visible.indexOf(index)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(listing: Listing, visible: IntArray, selected: Int) {
        // Ricostruisco tutto solo se cambiano elenco o filtro; per la sola selezione aggiorno le
        // due righe interessate, così la digitazione resta fluida anche con migliaia di voci.
        val structural = listing.seq != this.listing.seq || !visible.contentEquals(this.visible)
        this.listing = listing
        this.visible = visible
        if (structural) {
            this.selected = selected
            notifyDataSetChanged()
        } else {
            setSelected(selected)
        }
    }

    fun setSelected(index: Int) {
        if (index == selected) return
        val old = positionOf(selected)
        selected = index
        if (old >= 0) notifyItemChanged(old)
        val now = positionOf(index)
        if (now >= 0) notifyItemChanged(now)
    }

    /**
     * Avanzamento delle voci. Vale solo se riferito all'elenco mostrato ([ListingProgress.seq]):
     * uno arrivato per un elenco diverso resta in attesa e si applica quando l'elenco lo raggiunge.
     * Ridisegno soltanto le righe il cui valore cambia, con un payload che non tocca il resto.
     */
    fun submitProgress(progress: ListingProgress) {
        val before = effective(this.progress)
        this.progress = progress
        val after = effective(progress)
        if (before == after) return
        for (index in before.keys + after.keys) {
            if (before[index] == after[index]) continue
            val position = positionOf(index)
            if (position >= 0) notifyItemChanged(position, PAYLOAD_PROGRESS)
        }
    }

    private fun effective(p: ListingProgress): Map<Int, Int> = if (p.seq == listing.seq) p.permille else emptyMap()

    private fun progressOf(index: Int): Int = if (progress.seq == listing.seq) progress.of(index) else -1

    override fun getItemCount(): Int = visible.size

    override fun getItemId(position: Int): Long = (listing.seq.toLong() shl 32) or indexAt(position).toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        view.isFocusable = focusable
        view.isFocusableInTouchMode = false
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = indexAt(position)
        val entry = listing.entries[index]
        holder.bind(entry, index == selected)
        holder.bindProgress(progressOf(index))
        holder.itemView.setOnClickListener { onOpen(index) }
        holder.itemView.setOnLongClickListener {
            onSelect(index)
            true
        }
        holder.itemView.onFocusChangeListener = if (focusable) {
            View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) onSelect(index) }
        } else {
            null
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        // Aggiornamento del solo avanzamento: niente rebind completo (icona, testi, listener).
        if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_PROGRESS }) {
            holder.bindProgress(progressOf(indexAt(position)))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.entry_icon)
        private val name: TextView = view.findViewById(R.id.entry_name)
        private val detail: TextView = view.findViewById(R.id.entry_detail)
        private val progressBar: ProgressBar = view.findViewById(R.id.entry_progress)

        /** [permille] in 1..999 mostra la barra, qualunque altro valore la nasconde. */
        fun bindProgress(permille: Int) {
            if (permille in 1..999) {
                progressBar.progress = permille
                progressBar.contentDescription = itemView.context.getString(R.string.entry_progress_desc, permille / 10)
                progressBar.visibility = View.VISIBLE
            } else {
                progressBar.visibility = View.GONE
            }
        }

        fun bind(entry: Entry, isSelected: Boolean) {
            icon.setImageResource(iconFor(entry.kind))
            name.text = entry.name
            val text = detailText(entry)
            detail.text = text
            detail.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            itemView.isActivated = isSelected
        }

        private fun detailText(entry: Entry): String {
            val ctx = itemView.context
            val parts = ArrayList<String>(3)
            entry.detail?.takeIf { it.isNotBlank() }?.let { parts += it }
            if (!entry.kind.isContainer && entry.size >= 0) parts += Formatter.formatShortFileSize(ctx, entry.size)
            if (entry.kind != EntryKind.VOLUME && entry.modified > 0) {
                parts += DateFormat.getDateFormat(ctx).format(Date(entry.modified))
            }
            return parts.joinToString("  ·  ")
        }
    }

    companion object {
        private val PAYLOAD_PROGRESS = Any()

        fun iconFor(kind: EntryKind): Int = when (kind) {
            EntryKind.VOLUME -> R.drawable.ic_usb
            EntryKind.DIRECTORY -> R.drawable.ic_folder
            EntryKind.VIDEO -> R.drawable.ic_movie
            EntryKind.AUDIO -> R.drawable.ic_music
            EntryKind.IMAGE -> R.drawable.ic_image
            EntryKind.OTHER -> R.drawable.ic_file
        }
    }
}
