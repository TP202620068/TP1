package com.example.tp1.search

import org.junit.Assert.*
import org.junit.Test

class SearchStateTest {
    @Test fun onlySubmittedQueriesSwitchToResultsAndInvalidEditsCannotSubmit() {
        assertFalse(SearchState(draft = "question").showingResults)
        assertTrue(SearchState(draft = "question").canSubmit)
        assertFalse(SearchState(draft = " \n ").canSubmit)
        assertFalse(SearchState(draft = "keep", inputInvalid = true).canSubmit)
        assertTrue(SearchState(submitted = "captured", draft = "newer").showingResults)
    }

    @Test fun excerptsCollapseWhitespaceAndBoundWholeUnicodeCodePoints() {
        assertEquals("First line next line", searchExcerpt("  First\nline\t next line  "))
        val text = "😀".repeat(281)
        assertEquals("😀".repeat(280) + "…", searchExcerpt(text))
        assertEquals("😀".repeat(280), searchExcerpt("😀".repeat(280)))
        assertEquals("<quoted> evidence", searchExcerpt("<quoted> evidence"))
    }
}
