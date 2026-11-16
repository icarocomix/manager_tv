package com.icarocomix.icaromanagertv.common.search

import com.icarocomix.icaromanagertv.common.protocol.Entry
import java.text.Normalizer

/**
 * Ricerca per nome condivisa da TV e telefono: entrambi filtrano lo stesso elenco con la stessa
 * regola, così il telefono mostra il risultato mentre si digita senza attendere la TV, e la TV
 * mostra esattamente le stesse voci.
 *
 * Regola: senza distinzione tra maiuscole/minuscole e accenti, separatori (punto, trattino,
 * underscore) equivalenti allo spazio, tutte le parole della query devono comparire nel nome.
 * "matrix 1999" trova "The.Matrix.(1999).mkv".
 */
object SearchText {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[\\s._\\-]+")

    fun normalize(text: String): String {
        // Decompongo (NFD) e tolgo i segni diacritici: "Città" → "citta".
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "").lowercase().replace(SEPARATORS, " ").trim()
    }

    /** Parole normalizzate della query; lista vuota = nessun filtro. */
    fun tokens(query: String): List<String> =
        normalize(query).split(' ').filter { it.isNotEmpty() }

    fun matches(name: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val normalized = normalize(name)
        return tokens.all { normalized.contains(it) }
    }

    /** Indici (in [entries]) delle voci visibili con la query data, nell'ordine originale. */
    fun visibleIndices(entries: List<Entry>, query: String): IntArray {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return IntArray(entries.size) { it }
        val out = ArrayList<Int>()
        entries.forEachIndexed { i, e -> if (matches(e.name, tokens)) out += i }
        return out.toIntArray()
    }
}

/**
 * Ordinamento "naturale": i numeri contenuti nei nomi si confrontano per valore, così
 * "Episodio 2" precede "Episodio 10". Senza distinzione di maiuscole.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Confronto i blocchi numerici per valore: prima la lunghezza senza zeri iniziali,
                // poi cifra per cifra. Evito il parsing per non trabocchare con numeri lunghi.
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
