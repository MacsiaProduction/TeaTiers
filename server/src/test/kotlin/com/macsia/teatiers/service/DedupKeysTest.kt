package com.macsia.teatiers.service

import com.macsia.teatiers.domain.TeaType
import kotlin.test.Test
import kotlin.test.assertEquals

class DedupKeysTest {

    @Test
    fun `strips diacritics and lowercases the name and pinyin slug`() {
        assertEquals("longjing|longjing|GREEN", DedupKeys.of("Longjing", "lóngjǐng", TeaType.GREEN))
    }

    @Test
    fun `collapses whitespace in the name and removes non-alphanumerics from the slug`() {
        assertEquals(
            "da hong pao|dahongpao|OOLONG",
            DedupKeys.of("Da  Hong Pao", "dà hóng páo", TeaType.OOLONG),
        )
    }

    @Test
    fun `missing pinyin yields an empty slug segment`() {
        assertEquals("assam||BLACK", DedupKeys.of("Assam", null, TeaType.BLACK))
    }
}
