package com.macsia.teatiers.viewmodel

import com.macsia.teatiers.domain.model.FlavorDimension
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
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
    fun `toTea keeps every non-empty purchase draft and drops blank ones`() {
        val form = AddTeaForm(
            nameRu = "Чай",
            purchases = listOf(
                PurchaseDraft(kind = PurchaseKind.MARKETPLACE, url = "https://shop.example", label = "магазин"),
                PurchaseDraft(kind = PurchaseKind.TEXT, text = "Рынок", label = "поездка"),
                // blank => dropped by mapNotNull(toLocation)
                PurchaseDraft(kind = PurchaseKind.TEXT, text = "  ", label = "  "),
            ),
        )

        val tea = form.toTea()
        assertEquals(2, tea.purchaseLocations.size)
        val first = tea.purchaseLocations[0] as PurchaseLocation.Marketplace
        assertEquals("https://shop.example", first.url)
        assertEquals("магазин", first.label)
        val second = tea.purchaseLocations[1] as PurchaseLocation.FreeText
        assertEquals("Рынок", second.text)
        assertEquals("поездка", second.label)
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

    @Test
    fun `quick-rate plus extended dimensions cover every locked axis exactly once`() {
        val union = QuickRateDimensions + ExtendedRateDimensions
        assertEquals(FlavorDimension.entries.toSet(), union.toSet())
        assertEquals(FlavorDimension.entries.size, union.size) // no overlap, no gaps
        // The five axes the quick list omits must all be reachable under "show all".
        assertEquals(
            listOf(
                FlavorDimension.GRASSY,
                FlavorDimension.SPICY,
                FlavorDimension.SMOKY,
                FlavorDimension.EARTHY_NUTTY,
                FlavorDimension.UMAMI,
            ),
            ExtendedRateDimensions,
        )
    }

    @Test
    fun `collapsed flavor view hides unrated extended axes but keeps rated ones`() {
        val flavors = mapOf(
            FlavorDimension.SWEETNESS to 4, // quick — never part of the extended list
            FlavorDimension.SMOKY to 3, // extended + rated => stays visible when collapsed
            FlavorDimension.UMAMI to 0, // extended + zero => hidden when collapsed
        )

        val collapsed = visibleExtendedDimensions(flavors, expanded = false)
        assertEquals(listOf(FlavorDimension.SMOKY), collapsed)

        val expanded = visibleExtendedDimensions(flavors, expanded = true)
        assertEquals(ExtendedRateDimensions, expanded)
    }

    @Test
    fun `collapsed view is empty when no extended axis is rated`() {
        assertTrue(visibleExtendedDimensions(emptyMap(), expanded = false).isEmpty())
        assertTrue(
            visibleExtendedDimensions(mapOf(FlavorDimension.BITTERNESS to 5), expanded = false).isEmpty(),
        )
    }

    @Test
    fun `Tea toForm preserves every editable field`() {
        val tea = Tea(
            id = "x",
            nameRu = "Да Хун Пао",
            nameZh = "大红袍",
            pinyin = "Dà Hóng Páo",
            nameEn = "Da Hong Pao",
            type = TeaType.OOLONG,
            origin = "Уишань",
            shortBlurb = "blurb (catalog/AI; not edited)",
            flavor = listOf(FlavorScore(FlavorDimension.ROASTED, 5), FlavorScore(FlavorDimension.FLORAL, 2)),
            notes = "хорошо после обеда",
            purchaseLocations = listOf(
                PurchaseLocation.FreeText("Рынок", "поездка"),
                PurchaseLocation.Marketplace("https://example.com", "интернет"),
            ),
        )

        val form = tea.toForm()

        assertEquals("Да Хун Пао", form.nameRu)
        assertEquals("Da Hong Pao", form.nameEn)
        assertEquals("Dà Hóng Páo", form.pinyin)
        assertEquals("大红袍", form.nameZh)
        assertEquals(TeaType.OOLONG, form.type)
        assertEquals("Уишань", form.origin)
        assertEquals("хорошо после обеда", form.notes)
        assertEquals(mapOf(FlavorDimension.ROASTED to 5, FlavorDimension.FLORAL to 2), form.flavors)
        assertNull(form.tierId) // tier picker is hidden in edit mode
        assertEquals(2, form.purchases.size)
        assertEquals(PurchaseKind.TEXT, form.purchases[0].kind)
        assertEquals("Рынок", form.purchases[0].text)
        assertEquals("поездка", form.purchases[0].label)
        assertEquals(PurchaseKind.MARKETPLACE, form.purchases[1].kind)
        assertEquals("https://example.com", form.purchases[1].url)
        assertEquals("интернет", form.purchases[1].label)
    }

    @Test
    fun `toForm then toTea round-trips the user-entered fields`() {
        val original = Tea(
            id = "x",
            nameRu = "Чай",
            nameZh = null,
            pinyin = null,
            nameEn = "Tea",
            type = TeaType.GREEN,
            origin = "Уезд",
            flavor = listOf(FlavorScore(FlavorDimension.GRASSY, 3)),
            notes = "заметка",
            purchaseLocations = listOf(PurchaseLocation.FreeText("Лавка", null)),
        )

        val rebuilt = original.toForm().toTea()

        assertEquals(original.nameRu, rebuilt.nameRu)
        assertEquals(original.nameEn, rebuilt.nameEn)
        assertEquals(original.nameZh, rebuilt.nameZh)
        assertEquals(original.pinyin, rebuilt.pinyin)
        assertEquals(original.type, rebuilt.type)
        assertEquals(original.origin, rebuilt.origin)
        assertEquals(original.notes, rebuilt.notes)
        assertEquals(original.flavor, rebuilt.flavor)
        assertEquals(original.purchaseLocations, rebuilt.purchaseLocations)
        // toTea always mints a new user- id; the round-trip is over user-edited fields, not the id.
        assertTrue(rebuilt.id.startsWith("user-"))
    }
}
