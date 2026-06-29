package com.macsia.teatiers.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogTeaDetailTest {

    private fun detailWith(descriptions: List<CatalogDescription>) = CatalogTeaDetail(
        id = 1, type = TeaType.GREEN, originCountry = null, region = null, cultivar = null,
        oxidationMin = null, oxidationMax = null, brand = null, image = null,
        names = emptyList(), descriptions = descriptions, flavors = emptyList(),
        provenance = CatalogProvenance("curated", null, null, "verified", null),
    )

    @Test
    fun `forDeviceLanguage maps the bare zh subtag to the catalog zh-Hans key`() {
        assertEquals(CatalogLocale.ZH_HANS, CatalogLocale.forDeviceLanguage("zh"))
        assertEquals(CatalogLocale.RU, CatalogLocale.forDeviceLanguage("ru")) // others pass through
        assertEquals("fr", CatalogLocale.forDeviceLanguage("fr"))
    }

    @Test
    fun `a Chinese-locale device gets the zh-Hans description, not the ru fallback`() {
        val detail = detailWith(
            listOf(
                CatalogDescription(CatalogLocale.RU, "по-русски", null, null, null),
                CatalogDescription(CatalogLocale.ZH_HANS, "中文", null, null, null),
            ),
        )

        // "zh" is what Locale.getLanguage() yields on a Chinese device; mapped, it must hit zh-Hans.
        val picked = detail.descriptionFor(CatalogLocale.forDeviceLanguage("zh"))

        assertEquals("中文", picked?.short)
    }
}
