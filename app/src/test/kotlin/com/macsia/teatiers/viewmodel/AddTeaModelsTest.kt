package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.TeaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddTeaModelsTest {

    @Test
    fun `toTea trims names, drops blanks, and filters zero-intensity flavors`() {
        val form = AddTeaForm(
            nameRu = "  Да Хун Пао  ",
            nameEn = "   ",
            pinyin = "Dà Hóng Páo",
            nameZh = "",
            type = TeaType.OOLONG,
            origin = " Уишань ",
            notes = "",
            flavors = mapOf(
                FlavorDimension.ROASTED to 5,
                FlavorDimension.SWEETNESS to 0,
                FlavorDimension.FLORAL to 2,
            ),
        )

        val tea = form.toTea()

        assertEquals("Да Хун Пао", tea.nameRu)
        assertNull(tea.nameEn)
        assertEquals("Dà Hóng Páo", tea.pinyin)
        assertNull(tea.nameZh)
        assertEquals("Уишань", tea.origin)
        assertNull(tea.notes)
        assertEquals(TeaType.OOLONG, tea.type)
        assertEquals(listOf(FlavorDimension.ROASTED, FlavorDimension.FLORAL), tea.flavor.map { it.dimension })
        assertEquals(listOf(5, 2), tea.flavor.map { it.intensity })
        assertTrue(tea.id.startsWith("user-"))
        assertTrue(tea.purchaseLocations.isEmpty())
    }

    @Test
    fun `toTea includes a purchase only when the toggle is on`() {
        val form = AddTeaForm(
            nameRu = "Чай",
            includePurchase = true,
            purchase = PurchaseDraft(kind = PurchaseKind.MARKETPLACE, url = "https://shop.example", label = "магазин"),
        )

        val tea = form.toTea()
        assertEquals(1, tea.purchaseLocations.size)
        val location = tea.purchaseLocations.first()
        assertTrue(location is PurchaseLocation.Marketplace)
        assertEquals("https://shop.example", (location as PurchaseLocation.Marketplace).url)
        assertEquals("магазин", location.label)

        assertTrue(form.copy(includePurchase = false).toTea().purchaseLocations.isEmpty())
    }

    @Test
    fun `toLocation returns null for blank input and nulls a blank label`() {
        assertNull(PurchaseDraft(kind = PurchaseKind.TEXT, text = "   ").toLocation())
        assertNull(PurchaseDraft(kind = PurchaseKind.MARKETPLACE, url = "").toLocation())

        val location = PurchaseDraft(kind = PurchaseKind.TEXT, text = "Рынок", label = "  ").toLocation()
        assertTrue(location is PurchaseLocation.FreeText)
        assertEquals("Рынок", (location as PurchaseLocation.FreeText).text)
        assertNull(location.label)
    }

    @Test
    fun `isValid requires a non-blank ru name`() {
        assertFalse(AddTeaForm(nameRu = "   ").isValid)
        assertTrue(AddTeaForm(nameRu = "Чай").isValid)
    }
}
