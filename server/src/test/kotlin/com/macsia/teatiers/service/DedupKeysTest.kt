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

    @Test
    fun `keeps Cyrillic intact so names differing only by й-и or ё-е are distinct keys`() {
        // foldDiacritics must NOT collapse Cyrillic (it matches Postgres f_unaccent, which leaves it
        // alone): 'й'/'и' and 'ё'/'е' are different letters, so genuinely distinct Russian tea names
        // must not share one dedup_key (the old blanket NFD + combining-mark strip merged them).
        assertEquals("чай||GREEN", DedupKeys.of("Чай", null, TeaType.GREEN))
        assertEquals("чаи||GREEN", DedupKeys.of("Чаи", null, TeaType.GREEN))
        assertEquals("ёлка||OTHER", DedupKeys.of("Ёлка", null, TeaType.OTHER))
    }
}
