package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.TeaDetailDto
import com.macsia.teatiers.data.remote.dto.TeaImageDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogMappersTest {

    private fun detail(image: TeaImageDto? = null, images: List<TeaImageDto> = emptyList()) =
        TeaDetailDto(id = 1, type = "GREEN", image = image, images = images)

    @Test
    fun `maps the full images list and keeps image as the first`() {
        val d = detail(
            images = listOf(
                TeaImageDto(url = "a.jpg", license = "CC BY-SA 4.0", sourceUrl = "https://a"),
                TeaImageDto(url = "b.jpg", license = "CC BY 4.0", sourceUrl = "https://b"),
            ),
        ).toDomain()

        assertEquals(listOf("a.jpg", "b.jpg"), d.images.map { it.url })
        assertEquals("a.jpg", d.image?.url)
    }

    @Test
    fun `falls back to the single back-compat image when no list is sent`() {
        val d = detail(image = TeaImageDto(url = "only.jpg", license = null, sourceUrl = null)).toDomain()

        assertEquals(listOf("only.jpg"), d.images.map { it.url })
        assertEquals("only.jpg", d.image?.url)
    }

    @Test
    fun `no images yields an empty list and a null image`() {
        val d = detail().toDomain()

        assertTrue(d.images.isEmpty())
        assertNull(d.image)
    }

    @Test
    fun `maps harvestYear through and defaults to null when the server omits it`() {
        // The server publishes harvestYear on the catalog detail; the client used to drop it silently.
        assertEquals(2021, TeaDetailDto(id = 1, type = "GREEN", harvestYear = 2021).toDomain().harvestYear)
        assertNull(detail().toDomain().harvestYear)
    }
}
