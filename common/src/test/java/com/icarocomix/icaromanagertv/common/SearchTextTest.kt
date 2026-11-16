package com.icarocomix.icaromanagertv.common

import com.icarocomix.icaromanagertv.common.protocol.Entry
import com.icarocomix.icaromanagertv.common.protocol.EntryKind
import com.icarocomix.icaromanagertv.common.search.NaturalOrder
import com.icarocomix.icaromanagertv.common.search.SearchText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun ignoresCaseAccentsAndSeparators() {
        val tokens = SearchText.tokens("matrix 1999")
        assertTrue(SearchText.matches("The.Matrix.(1999).mkv", tokens))
        assertTrue(SearchText.matches("Città_di_notte.jpg", SearchText.tokens("CITTA notte")))
        assertFalse(SearchText.matches("The.Matrix.Reloaded.2003.mkv", tokens))
    }

    @Test
    fun emptyQueryShowsEverything() {
        val entries = listOf(Entry("a", EntryKind.OTHER), Entry("b", EntryKind.OTHER))
        assertArrayEquals(intArrayOf(0, 1), SearchText.visibleIndices(entries, "   "))
    }

    @Test
    fun visibleIndicesKeepOriginalPositions() {
        val entries = listOf(
            Entry("Cartoni", EntryKind.DIRECTORY),
            Entry("Film", EntryKind.DIRECTORY),
            Entry("film-vacanze.mp4", EntryKind.VIDEO),
        )
        assertArrayEquals(intArrayOf(1, 2), SearchText.visibleIndices(entries, "film"))
    }

    @Test
    fun naturalOrderComparesNumbersByValue() {
        val sorted = listOf("Episodio 10.mkv", "episodio 2.mkv", "Episodio 1.mkv", "Episodio 02b.mkv")
            .sortedWith(NaturalOrder)
        assertEquals(listOf("Episodio 1.mkv", "episodio 2.mkv", "Episodio 02b.mkv", "Episodio 10.mkv"), sorted)
    }
}
