package com.macsia.teatiers.data.backup

import com.macsia.teatiers.data.db.BoardEntity
import com.macsia.teatiers.data.db.FlavorEntity
import com.macsia.teatiers.data.db.PhotoEntity
import com.macsia.teatiers.data.db.PlacementEntity
import com.macsia.teatiers.data.db.PurchaseLocationEntity
import com.macsia.teatiers.data.db.SeedEntities
import com.macsia.teatiers.data.db.TeaEntity
import com.macsia.teatiers.data.db.TierEntity
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackupModelsTest {

    private fun snapshot(): SeedEntities = SeedEntities(
        boards = listOf(BoardEntity("b1", "Daily", 0)),
        tiers = listOf(TierEntity("t1", "b1", "S", 0, 0xFFFF8800)),
        teas = listOf(
            TeaEntity("tea1", "Те Гуань Инь", null, "tie guan yin", null, "OOLONG", "Fujian", null, "nice"),
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
        assertEquals(original.teas, restored.teas)
        assertEquals(original.placements, restored.placements)
        assertEquals(original.flavors, restored.flavors)
        assertEquals(original.purchases, restored.purchases)
        assertEquals("/files/tea_photos/new.png", restored.photos.first { it.id == "ph1" }.uri)
        assertEquals("https://x/y.jpg", restored.photos.first { it.id == "ph2" }.uri)
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
