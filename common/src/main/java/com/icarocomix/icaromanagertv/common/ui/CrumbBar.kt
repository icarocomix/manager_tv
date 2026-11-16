package com.icarocomix.icaromanagertv.common.ui

import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.icarocomix.icaromanagertv.common.R

/**
 * Barra del percorso (breadcrumb) condivisa: "Archivi › Disco › Film › Azione". Ogni elemento
 * richiama [onCrumb] con la profondità a cui risalire (0 = radice); l'ultimo è la cartella
 * corrente ed è evidenziato con il colore d'accento.
 */
class CrumbBar(
    private val scroll: HorizontalScrollView,
    private val container: LinearLayout,
    private val onCrumb: (depth: Int) -> Unit,
) {
    private var shown: List<String>? = null

    fun render(crumbs: List<String>, searchLabel: String? = null) {
        val labels = listOf(container.context.getString(R.string.root_label)) + crumbs
        val full = if (searchLabel != null) labels + searchLabel else labels
        if (full == shown) return
        shown = full
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        full.forEachIndexed { depth, label ->
            val chip = inflater.inflate(R.layout.item_crumb, container, false) as TextView
            chip.text = label
            val isLast = depth == full.lastIndex
            if (isLast) chip.setTextColor(container.context.getColor(R.color.accent))
            // L'etichetta di ricerca non è un livello del percorso: non risale da nessuna parte.
            if (depth < labels.size && !(isLast && searchLabel == null)) {
                chip.setOnClickListener { onCrumb(depth) }
            } else {
                chip.isFocusable = false
            }
            container.addView(chip)
        }
        // Porto in vista l'ultimo elemento: nei percorsi lunghi è quello che interessa. Uso
        // scrollTo e non fullScroll, che sposterebbe anche il focus del telecomando sulla barra.
        scroll.post { scroll.scrollTo(container.width, 0) }
    }
}
