package com.macsia.teatiers.viewmodel

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure decision tests for [isSearchableQuery] (UX-P1-4). */
class CatalogSearchModelsTest {

    @Test
    fun `a single Han character is searchable`() {
        assertTrue(isSearchableQuery("茶")) // "tea" — a complete token on its own
    }

    @Test
    fun `a single Latin or Cyrillic character is not searchable`() {
        assertFalse(isSearchableQuery("a"))
        assertFalse(isSearchableQuery("л"))
    }

    @Test
    fun `an empty query is not searchable`() {
        assertFalse(isSearchableQuery(""))
    }

    @Test
    fun `any query at or above the minimum length is searchable regardless of script`() {
        assertTrue(isSearchableQuery("ab"))
        assertTrue(isSearchableQuery("普洱")) // two Han characters
        assertTrue(isSearchableQuery("茶a")) // mixed, already at the length floor
    }
}
