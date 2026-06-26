package com.macsia.teatiers.data.backup

import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.CatalogRefEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.SeedEntities
import com.macsia.teatiers.data.db.TeaSampleEntity
import com.macsia.teatiers.data.db.TierEntity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupModelsTest {

    private fun snapshot(): SeedEntities = SeedEntities(
        boards = listOf(BoardEntity("b1", "Daily", 0)),
        tiers = listOf(TierEntity("t1", "b1", "S", 0, 0xFFFF8800)),
        catalogRefs = listOf(CatalogRefEntity(id = 42L, type = "OOLONG", brand = "Acme", fetchedAtEpochMs = 7L)),
        teas = listOf(
            // Catalog-linked + DONE so the round-trip assertions also guard the v5 enrichment fields.
            TeaSampleEntity(
                "tea1", "Те Гуань Инь", null, "tie guan yin", null, "OOLONG", "Fujian", null, "nice",
                catalogTeaId = 42L, enrichmentState = "DONE",
            ),
        ),
        placements = listOf(PlacementEntity("p1", "b1", "tea1", "t1", 0)),
        flavors = listOf(FlavorEntity("tea1", "FLORAL", 4, 0)),
        purchases = listOf(PurchaseLocationEntity("pl1", "tea1", 0, "TEXT", null, "market")),
        photos = listOf(
            PhotoEntity("ph1", "tea1", "/data/tea_photos/abc.png", 0, "USER", null, null, 123L),
            PhotoEntity("ph2", "tea1", "https://x/y.jpg", 1, "CATALOG", "CC", "https://x", 456L),
        ),
    )

    @Test
    fun `toSeedEntities dedups duplicate flavor rows on the composite key (finding 15)`() {
        // A malformed/hand-edited bundle with two rows for the same (teaId, dimension) would otherwise
        // abort the whole replaceAll on the tea_flavors composite PK.
        val bundle = BackupBundle(
            teas = listOf(BackupTea(id = "t", type = "GREEN")),
            flavors = listOf(
                BackupFlavor("t", "BITTERNESS", 2, 0),
                BackupFlavor("t", "BITTERNESS", 4, 1),
            ),
        )

        val seed = bundle.toSeedEntities(emptyMap())

        assertEquals(1, seed.flavors.size, "duplicate (teaId, dimension) must collapse to one row")
        assertEquals(2, seed.flavors.first().intensity, "the first occurrence wins")
    }

    @Test
    fun `file photo is bundled while url photo keeps its uri`() {
        val bundle = snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")

        val filePhoto = bundle.photos.first { it.id == "ph1" }
        assertEquals("ph1.png", filePhoto.bundledFileName)
        assertNull(filePhoto.uri)

        val urlPhoto = bundle.photos.first { it.id == "ph2" }
        assertNull(urlPhoto.bundledFileName)
        assertEquals("https://x/y.jpg", urlPhoto.uri)

        assertEquals(listOf("ph1.png"), bundle.bundledPhotoNames())
        assertEquals(BACKUP_FORMAT_VERSION, bundle.formatVersion)
        assertEquals(1_000L, bundle.exportedAtEpochMs)
    }

    @Test
    fun `round trip restores every table and rewrites the bundled photo path`() {
        val original = snapshot()
        val bundle = original.toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")
        val restored = bundle.toSeedEntities(mapOf("ph1.png" to "/files/tea_photos/new.png"))

        assertEquals(original.boards, restored.boards)
        assertEquals(original.tiers, restored.tiers)
        assertEquals(original.catalogRefs, restored.catalogRefs)
        assertEquals(original.teas, restored.teas)
        assertEquals(original.placements, restored.placements)
        assertEquals(original.flavors, restored.flavors)
        assertEquals(original.purchases, restored.purchases)
        assertEquals("/files/tea_photos/new.png", restored.photos.first { it.id == "ph1" }.uri)
        assertEquals("https://x/y.jpg", restored.photos.first { it.id == "ph2" }.uri)
    }

    @Test
    fun `catalog linkage and enrichment state survive a round trip`() {
        val bundle = snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")
        val restored = bundle.toSeedEntities(mapOf("ph1.png" to "/files/x.png")).teas.single()

        assertEquals(42L, restored.catalogTeaId)
        assertEquals("DONE", restored.enrichmentState)
    }

    @Test
    fun `a pre-v5 backup without the enrichment fields restores as an un-enriched custom tea`() {
        // An old bundle JSON predating the v5 columns must still decode (additive, defaulted fields).
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """
            {"formatVersion":1,"teas":[{"id":"t","nameRu":"Чай","type":"GREEN"}]}
        """.trimIndent()
        val tea = json.decodeFromString<BackupBundle>(legacy).toSeedEntities(emptyMap()).teas.single()

        assertNull(tea.catalogTeaId)
        assertEquals("NONE", tea.enrichmentState)
    }

    @Test
    fun `a bundled photo missing from the restored files is dropped, url photo kept`() {
        val bundle = snapshot().toBundle(exportedAtEpochMs = 1_000L, appVersion = "0.1.0")
        val restored = bundle.toSeedEntities(restoredPaths = emptyMap())
        assertEquals(listOf("ph2"), restored.photos.map { it.id })
    }

    @Test
    fun `full json encode-decode pipeline preserves the snapshot`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = snapshot()
        val text = json.encodeToString(original.toBundle(2_000L, "0.1.0"))
        val decoded = json.decodeFromString<BackupBundle>(text)
        val restored = decoded.toSeedEntities(mapOf("ph1.png" to "/files/tea_photos/x.png"))

        assertEquals(original.teas, restored.teas)
        assertEquals(original.placements, restored.placements)
        assertEquals(original.tiers, restored.tiers)
        assertEquals(2, restored.photos.size)
    }
}
