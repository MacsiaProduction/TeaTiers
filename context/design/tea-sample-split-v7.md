# Design: Canonical `CatalogTeaRef` vs user `TeaSample` split (P1-1) + any-language naming (P1-2)

Status: design + lockable decision only. **No implementation in this doc.** Targets Room schema **v7**, package `com.macsia.teatiers.data.db`.

Implementation baseline: server `21fda80` (decision #136 foundation landed). **Identity model amended 2026-06-21 — see the amendment box below.** The lossless-migration mechanics (§4, §6.4, §7) remain authoritative; the *catalog-ref identity type* and the resolution of Q1/Q2 are superseded by the amendment.

> ### ⚠ AMENDMENT 2026-06-21 (decision #137-C2): catalog-ref identity is UUID, not `Long`
>
> Server decision #136 introduced `tea.public_id UUID` as the only client-facing catalog identity, and decision #137 corrected it to be reproducible from seed (frozen UUIDs, numeric resolves only through an immutable legacy map). That makes this doc's `catalog_refs.id: Long = server tea.id` a stale foreign-key contract. **The following overrides every `Long`/`id = server tea.id`/`catalogTeaId`-as-ref-id mention in §2–§7:**
>
> 1. **`CatalogRefEntity` primary key is `publicId` (UUID, stored as canonical lowercase text or a 16-byte value, deterministically encoded).** There is no `Long` ref primary key. An optional `legacyNumericId: Long?` column exists ONLY to upgrade v6 rows and to call the numeric compat endpoint exactly once during reconciliation.
> 2. **`tea_samples.catalogRefId` becomes `catalogRefPublicId: String?`** (UUID text), nullable FK → `catalog_refs.publicId`, `ON DELETE SET NULL`. All new sample→ref links use the UUID only.
> 3. **Migration backfills ref stubs as LEGACY-UNRESOLVED refs.** v6 only knows `teas.catalogTeaId` (a server `Long`). The migration creates one stub per DISTINCT non-null `catalogTeaId` with `legacyNumericId = catalogTeaId` and **`publicId = NULL-equivalent placeholder` is not allowed** — instead the stub row is keyed by a deterministic *local* placeholder UUID derived from the legacy id (so the PK/FK hold), flagged `resolved = 0`. Samples link to that placeholder.
> 4. **A post-upgrade reconciliation pass** (runs after migration, off the UI thread, idempotent, retriable) calls the server numeric compat endpoint (`GET /teas/{legacyNumericId}` → returns `publicId`), then rewrites the stub's real `publicId` and re-points sample FKs — **without ever touching personal sample data** (notes, flavor, purchases, photos, names, ranks). Until reconciled, the app shows cached/personal data; catalog facts fill in after resolution. Reconciliation failure is non-fatal and re-attempted on next launch/refresh.
> 5. **Wire/domain/cache must decode `publicId`, `status`, `supersededByPublicId`** and use a `/by-public-id/{uuid}` detail/poll endpoint for all NEW lookups; the numeric path is compat-only. A `merged` ref follows `supersededByPublicId`; a `retracted` ref shows a tombstone, never a hard 404.
> 6. **Backup format v2 carries `publicId` + optional `legacyNumericId`** per ref (not a bare `Long`). The v6→v7 up-converter (§6.4) emits placeholder-UUID legacy-unresolved refs identically to the SQL migration; restore is followed by the same reconciliation pass.
>
> **This resolves open questions Q1 and Q2 (do NOT duplicate refreshable catalog facts into personal rows):**
> - **Q1 →** `shortBlurb`, catalog flavor, server `enrichmentState`, `type`/`origin` when catalog-known, provenance (`source/sourceUrl/license`), `verificationStatus`, `confidence` live ONLY on `catalog_refs`. The sample keeps personal-only fields: `notes`, personal flavor, `vendor`, `product`, `harvestYear`, `batch`, `grade`, purchases, photos, and its own resolve `enrichmentState`.
> - **Q2 →** catalog names live ONLY on `catalog_refs` (option (a)); `tea_sample_names` holds personal names only. The display resolver uses the personal primary name first and falls back to ref names. Catalog refresh touches ref rows only and can never overwrite a personal name/note/flavor. (This removes the §6.1/§6.2 "catalog names into `tea_sample_names`" path and the Q2 option-(b) `source='catalog'` rows.)
>
> The §4 id-reuse trick still applies to **`tea_samples.id` = v6 `teas.id`** (personal-side losslessness is unchanged). Only the *ref* identity moves to UUID. The migration/backup/CI tests in §7 must additionally assert: legacy-unresolved stubs created with `legacyNumericId`; reconciliation rewrites `publicId` and re-points sample FKs without mutating personal data; an un-reconciled sample still renders its personal primary name.

> ### ✅ DECISIONS LOCKED 2026-06-23 (owner) — Q1–Q8 + updater resolved; implement to THIS shape
>
> The amendment above resolves Q1/Q2. The §8 open questions are now ruled; together with the amendment they
> are the authoritative shape — every `Long` / single-name / `id = server tea.id` example in §2–§8 is
> superseded where it conflicts.
>
> - **Q4 (names) → MULTIPLE aliases per locale.** `tea_sample_names` gets a surrogate PK `id` (autogen) +
>   `UNIQUE(sampleId, locale, value)` — NOT `PK(sampleId, locale)`; a sample may hold several names in one
>   locale. The §5 display resolver still selects exactly one primary.
> - **Q7 (refresh writer) → SHIP WITH v7.** A catalog-refresh-by-id writer lands in the same milestone so
>   migrated ref stubs populate (overwrites ref facts only, never personal data). Do NOT park migrated linked
>   samples on `PENDING` as a stopgap.
> - **Q3 → custom add is ALWAYS create-new** (name-match is a non-blocking suggestion, never auto-merge).
> - **Q5 → KEEP `placements.teaId` column name** (defer the cosmetic `sampleId` rename).
> - **Q6 → repo-enforced single `isPrimary`** per sample (no partial unique index).
> - **Q8 → Gradle Managed Device** for the migration CI gate (faithful SQLite/FK semantics).
> - **Updater (REL-P0-2) → Obtainium / GitHub releases is the trusted channel** until an offline Ed25519
>   signed manifest exists; the in-app updater is NOT the primary path and gets NO TLS leaf pin (a Let's
>   Encrypt rotation would brick it). Tracked outside this doc.

Owner-locked product decisions this doc reflects:
1. **Cross-board sharing kept** — one `TeaSample` is placed on many boards via many `placements` rows (`BoardPlacement → TeaSample`); preserves decisions.md #42 verbatim.
2. **Reuse-if-same-catalog-ref on re-add**; a new sample is created only on an explicit **"add another"**; **custom samples are never auto-merged** (name-match dedup is a non-blocking suggestion).
3. This is design + a lockable decision. Implementation is sequenced separately (see Recommended Sequence).

---

## 1. Problem and goal

Today one flat row (`teas` table, `TeaEntity` in `app/src/main/kotlin/com/macsia/teatiers/data/db/Entities.kt:64-80`) conflates two concepts:

- **The user's physical sample** — `notes`, `tea_flavors`, `purchase_locations`, `tea_photos`, and board `placements` all hang off `teas.id` with `ON DELETE CASCADE`.
- **The canonical catalog identity** — `catalogTeaId: Long?` plus catalog/AI-derived `shortBlurb`, and names/`type`/`origin` that enrichment overwrites from the server (`TeaDao.applyEnrichmentPatch`, `TeaDao.kt:247-274`).

A `UNIQUE` index on `teas.catalogTeaId` (`6.json` lines 172-182, decisions.md #101) makes "at most one user-tea per catalog id" a schema invariant. That **blocks the core P1-1 use case**: a user who buys two physical samples of the same catalog tea (different vendor / harvest year / batch) cannot keep both with independent notes, flavor, purchases, photos, and ranks. Worse, today's resolve-or-create path (`TeaBoardRepository.resolveTeaIdForMatch`, `TeaBoardRepository.kt:370`) silently **reuses** the existing tea and **drops the second add's typed fields** (`TeaDao.addTea` inserts only a placement when `tea == null`, `TeaDao.kt:381-394`).

Separately (P1-2), `nameRu` is the only required name (`AddTeaForm.isValid = nameRu.isNotBlank()`, `AddTeaModels.kt:88`; `teas.nameRu TEXT NOT NULL` in `6.json:104`) and the universal display title at 7+ read sites. A user cannot enter a tea known to them only as `大红袍` or only `Da Hong Pao`.

**Goals**

- **G1 (P1-1):** Many `TeaSample` rows may link to one cached `CatalogTeaRef`; each sample keeps independent personal data. Catalog refresh overwrites catalog facts only, never personal data.
- **G2 (P1-2):** A sample is valid with **≥1 non-blank name in any locale** (`ru | en | pinyin | zh-Hans`); a deterministic resolver picks the display title everywhere.
- **G3 (lossless upgrade):** Every v6 record — placements (incl. multi-board #42 and zero-board pool teas), tier rankings, notes, flavors, photos (incl. on-disk files), purchases, both custom and catalog-linked teas — survives v6→v7 with a tested `Migration(6,7)`. Release no longer has destructive fallback (`AppModule.kt:53-63`, decision #130 / P0-1), so a wrong migration **hard-crashes on launch with no recourse**.

**Non-goals (deferred):** richer `Purchase` (seller/date/price/qty); name aliases (multiple rows per locale); renaming `placements.teaId`→`sampleId` / `purchase_locations`→`purchases`; copying catalog-derived columns wholesale onto the ref at migrate time.

---

## 2. Target model + relationship diagram

```
                        catalog_refs (cached canonical, READ-ONLY)
                        id (Long = server tea.id), type, brand, region,
                        oxidation*, wikidataQid, verificationStatus,
                        confidence, enrichmentState (server LLM),
                        shortBlurb?, source/sourceUrl/license, fetchedAtEpochMs
                                 ^
                                 | catalogRefId  (nullable FK, ON DELETE SET NULL)
                                 | 0..1 ref  <----  N samples   (P1-1: many→one)
                                 |
   tea_sample_names             |
   (sampleId,locale) PK   N ----+----  tea_samples  (THE user item; one v6 tea → one sample)
   value, isPrimary, source     |      id (String = v6 teas.id, REUSED verbatim)
        ^ CASCADE                |      type, origin?, notes?, shortBlurb?, enrichmentState,
        |  >=1 name per sample   |      catalogRefId?, displayNamePref?,
        |                        |      vendor?, product?, harvestYear?, batch?, grade?
        |        +---------------+---------------+----------------+
        |        | CASCADE       | CASCADE       | CASCADE        | CASCADE
   tea_samples   v               v               v               v
             tea_flavors    purchase_locations  tea_photos     placements
             (sampleId,dim)  (id, sampleId,…)   (id,sampleId,  (id, boardId, teaId=sampleId,
                                                  uri,…)         tierId?, position)
                                                                   ^
                                                                   | boardId CASCADE
                                                                   |
                                                                boards ── tiers (boardId CASCADE)
                                                                          (placements.tierId: NO FK, #39)

catalog_cache  (unchanged, evictable offline search cache; NO FK to anything)
```

Cardinality changes from v6:
- `catalog_refs (0..1) ← (N) tea_samples` — **replaces** the v6 `UNIQUE(teas.catalogTeaId)`. The one-per-ref invariant is **dropped at the DB level** and moved into repo policy (decision 2).
- `boards (N) ←placements→ (N) tea_samples` — unchanged join, `placements.teaId` now means `sampleId`.
- Names: scalar `nameRu/nameZh/pinyin/nameEn` on `teas` → child rows in `tea_sample_names`.

Deleting/evicting a `catalog_refs` row must **never** delete a sample → `ON DELETE SET NULL` on `tea_samples.catalogRefId`. Deleting a board cascades tiers+placements only (sample survives). Deleting a sample cascades its names/flavors/purchases/photos/placements.

---

## 3. v7 Room entities (fields / keys / FKs)

> Note (resolves adversarial finding #2): the DDL below is the **intended shape**, but the migration MUST copy `createSql` verbatim from the generated `7.json`, not hand-transcribe it. `enrichmentState` carries a Room-emitted `DEFAULT 'NONE'`; a hand-written `CREATE TABLE` that omits the default fails `identityHash` validation. Build → commit `7.json` → paste its `createSql` strings into the migration.

### 3.1 `CatalogRefEntity` (`catalog_refs`) — cached canonical ref, read-only
- `@Entity(tableName="catalog_refs")`, `@PrimaryKey id: Long` (server `tea.id`, **not** autogenerated; shares `CatalogCacheEntity.id` space).
- Cached facts mirroring `TeaDetailDto`: `wikidataQid: String?`, `type: String` (NOT NULL), `originCountry/region/cultivar: String?`, `oxidationMin/Max: Int?`, `brand: String?`, `verificationStatus: String?`, `confidence: Double?`, `enrichmentState: String?` (server LLM lifecycle — **distinct** from the sample's), `shortBlurb: String?`, provenance `source/sourceUrl/license: String?`, `fetchedAtEpochMs: Long` (NOT NULL).
- No FK out. **No** `UNIQUE(catalogTeaId)` here. Row is OPTIONAL (custom samples have none). At migrate time only `id`+`type` are populated (everything else nullable; backfilled on next refresh).

### 3.2 `TeaSampleEntity` (`tea_samples`) — the user item
- `@PrimaryKey id: String` — **reuses v6 `teas.id` verbatim** (keeps all child FKs, photo ids/uris, and `$id-p$pos` purchase ids valid without rewriting child rows).
- `catalogRefId: Long?` — was `catalogTeaId`; nullable FK → `catalog_refs.id`, `onDelete = SET NULL`. **No UNIQUE.**
- Personal scalars kept on the sample: `type: String` (NOT NULL), `origin: String?`, `notes: String?`, `shortBlurb: String?` (see open question Q1), `enrichmentState: String` (NOT NULL, `DEFAULT 'NONE'` — the **sample's own** resolve lifecycle), `displayNamePref: String?`.
- New sample-identity fields (all nullable, NULL on migrated rows): `vendor: String?`, `product: String?`, `harvestYear: Int?`, `batch: String?`, `grade: String?`.
- Index: `index_tea_samples_catalogRefId`.
- **Names do not live here** (P1-2 → child table).

### 3.3 `TeaSampleNameEntity` (`tea_sample_names`) — personal localized names (P1-2)
- `@Entity(primaryKeys=["sampleId","locale"])` — at most one name per (sample, locale).
- `sampleId: String` (FK → `tea_samples.id`, `onDelete = CASCADE`); `locale: String` ∈ `ru|en|pinyin|zh-Hans` (same vocabulary as server `tea_name`); `value: String` (NOT NULL); `isPrimary: Int` (0/1, **exactly one per sample**, repo-enforced); `source: String?` (`user|catalog|ocr`).
- Index `index_tea_sample_names_sampleId`. **Invariant (asserted by migration + repo): every sample has ≥1 name row and exactly one `isPrimary=1`.**

### 3.4 Unchanged-structure tables, FK repointed
- `placements` — **keep table + `teaId` column name** (`teaId` now means sampleId; renaming is cosmetic and forces a rebuild — deferred). FK `teaId` → `tea_samples.id` CASCADE; `UNIQUE(boardId, teaId)`, `index_placements_teaId`, `index_placements_tierId` preserved. `tierId` keeps **no FK** (#39).
- `tea_flavors` (composite PK `(teaId,dimension)`), `purchase_locations`, `tea_photos` — FK `teaId` → `tea_samples.id` CASCADE; structure byte-identical.
- `boards`, `tiers`, `catalog_cache` — **byte-for-byte untouched.**

`@Database(version=7, exportSchema=true)`; entity list swaps `TeaEntity`→`TeaSampleEntity`, adds `TeaSampleNameEntity` + `CatalogRefEntity`. Relations: `TeaWithChildren`→`SampleWithChildren` (sample + names + flavors + purchases + photos + optional ref); `PlacementWithTea`→`PlacementWithSample` (the `.firstOrNull() ?: error(...)` path in `Relations.kt` unchanged).

---

## 4. The lossless `Migration(6, 7)`

### 4.1 Strategy

One `Migration(6,7)` in a new `data/db/Migrations.kt`, executed as **ordered DDL+DML inside Room's single `migrate()` transaction** (atomic — a failure rolls back to intact v6). The **id-reuse trick** is what makes it lossless and cheap: `tea_samples` reuses `teas.id` verbatim, so `placements.teaId`, `tea_flavors.teaId`, `purchase_locations.teaId`, `tea_photos.teaId` stay valid **without touching child rows**; photo files keep resolving; purchase ids (`$teaId-p$pos`) stay correct.

**Fixed ordering (load-bearing — resolves finding #11):**
1. Create `catalog_refs` + backfill stubs.
2. Create `tea_samples` + full copy from `teas`.
3. Create `tea_sample_names` + fan-out from `teas`.
4. Rebuild each child table (`placements`, `tea_flavors`, `purchase_locations`, `tea_photos`) to repoint its FK to `tea_samples`.
5. `DROP TABLE teas` (last, after all children repointed).
6. `PRAGMA foreign_key_check` (resolves finding #1 — turns a silent post-`migrate()` rollback + crash-loop into a debuggable failure inside the migration).

Room runs migrations with `PRAGMA foreign_keys` OFF, then re-validates `identityHash` against `7.json` and runs `foreign_key_check` after `migrate()` returns. We add an explicit `foreign_key_check` step at the end so an orphan link fails loudly during the migration.

### 4.2 Row mapping (every v6 record)

| v6 record | v7 destination | Notes |
|---|---|---|
| `teas` row (custom, `catalogTeaId=NULL`) | one `tea_samples` row, `catalogRefId=NULL`, no `catalog_refs` row | lossless; `type` copied verbatim (v6 `type` is NOT NULL → GREEN default never fires; assert in test, finding #12) |
| `teas` row (catalog-linked) | one `tea_samples` row, `catalogRefId=catalogTeaId`; one stub `catalog_refs` row | stub = `id`+`type` only |
| `teas.nameRu/nameEn/pinyin/nameZh` | up to 4 `tea_sample_names` rows | fan-out; **isPrimary by priority** (see 4.3) |
| `placements` (1 board) | unchanged rows; FK now → `tea_samples` | rankings (`tierId`,`position`) round-trip |
| `placements` (N boards, #42) | unchanged; each board's placement maps onto the one shared sample | cross-board sharing preserved verbatim |
| tea on **0 boards** (pool tea, `observeAllTeas`) | sample copied via `INSERT…SELECT FROM teas` with no placement join | survives (finding #11) |
| `tea_flavors` (composite PK) | unchanged rows; FK → `tea_samples` | explicit column list, no `SELECT *` |
| `purchase_locations` (`$teaId-p$pos` ids) | unchanged rows; FK → `tea_samples` | id reuse keeps derivation valid |
| `tea_photos` (uri = on-disk path) | unchanged rows; FK → `tea_samples` | ids/uris stable → `reconcile()` won't orphan files |

### 4.3 SQL steps (intended shape — final DDL pasted from `7.json`)

```sql
-- 1. catalog_refs + backfill stubs from DISTINCT non-null catalogTeaId
CREATE TABLE IF NOT EXISTS `catalog_refs` ( ... id INTEGER NOT NULL, type TEXT NOT NULL, ...,
  fetchedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(`id`) );      -- exact DDL from 7.json
INSERT OR IGNORE INTO `catalog_refs` (id, type, fetchedAtEpochMs)
  SELECT DISTINCT t.catalogTeaId, t.type, 0 FROM `teas` t WHERE t.catalogTeaId IS NOT NULL;

-- 2. tea_samples + full copy (explicit columns, NOT SELECT *)
CREATE TABLE IF NOT EXISTS `tea_samples` ( ... enrichmentState TEXT NOT NULL DEFAULT 'NONE', ...,
  FOREIGN KEY(`catalogRefId`) REFERENCES `catalog_refs`(`id`) ON DELETE SET NULL );  -- exact DDL from 7.json
CREATE INDEX IF NOT EXISTS `index_tea_samples_catalogRefId` ON `tea_samples` (`catalogRefId`);
INSERT INTO `tea_samples` (id, catalogRefId, type, origin, notes, shortBlurb, enrichmentState)
  SELECT id, catalogTeaId, type, origin, notes, shortBlurb, enrichmentState FROM `teas`;

-- 3. tea_sample_names fan-out (use raw value; do NOT silently drop blanks)
CREATE TABLE IF NOT EXISTS `tea_sample_names` ( ... PRIMARY KEY(`sampleId`,`locale`),
  FOREIGN KEY(`sampleId`) REFERENCES `tea_samples`(`id`) ON DELETE CASCADE );          -- exact DDL from 7.json
CREATE INDEX IF NOT EXISTS `index_tea_sample_names_sampleId` ON `tea_sample_names` (`sampleId`);
INSERT INTO `tea_sample_names` SELECT id,'ru',     nameRu, 0,'user' FROM `teas` WHERE nameRu  IS NOT NULL AND TRIM(nameRu) <>'';
INSERT INTO `tea_sample_names` SELECT id,'en',     nameEn, 0,'user' FROM `teas` WHERE nameEn  IS NOT NULL AND TRIM(nameEn) <>'';
INSERT INTO `tea_sample_names` SELECT id,'pinyin', pinyin, 0,'user' FROM `teas` WHERE pinyin  IS NOT NULL AND TRIM(pinyin)<>'';
INSERT INTO `tea_sample_names` SELECT id,'zh-Hans',nameZh, 0,'user' FROM `teas` WHERE nameZh  IS NOT NULL AND TRIM(nameZh)<>'';

-- 3a. SAFETY NET (resolves finding #4): any sample with ZERO name rows (all four blank/whitespace)
--     gets one ru row from the RAW (untrimmed) nameRu so the >=1-name invariant always holds.
INSERT OR IGNORE INTO `tea_sample_names`
  SELECT id,'ru', nameRu, 0,'user' FROM `teas`
  WHERE id NOT IN (SELECT sampleId FROM `tea_sample_names`);

-- 3b. EXACTLY-ONE-PRIMARY (resolves findings #4/#5): set isPrimary=1 on the highest-priority
--     present locale per sample (ru > en > pinyin > zh-Hans), NOT "ru always".
UPDATE `tea_sample_names` SET isPrimary=1
  WHERE rowid IN (
    SELECT n.rowid FROM `tea_sample_names` n
    WHERE n.locale = (
      SELECT m.locale FROM `tea_sample_names` m WHERE m.sampleId = n.sampleId
      ORDER BY CASE m.locale WHEN 'ru' THEN 0 WHEN 'en' THEN 1 WHEN 'pinyin' THEN 2 ELSE 3 END
      LIMIT 1));
-- (For the overwhelming v6 case — non-blank nameRu — this picks the ru row, so the post-migration
--  display title is byte-identical to v6. The priority pass only matters for the blank-ru edge case.)

-- 4. Repoint each child FK: create-new / explicit-column copy / drop-old / rename / recreate indices.
--    NEVER SELECT * (resolves findings #3/#8 — positional mapping silently corrupts on column reorder).
CREATE TABLE `placements_new` ( ...same cols, exact DDL from 7.json...,
  FOREIGN KEY(`boardId`) REFERENCES `boards`(`id`) ON DELETE CASCADE,
  FOREIGN KEY(`teaId`)   REFERENCES `tea_samples`(`id`) ON DELETE CASCADE );
INSERT INTO `placements_new` (id,boardId,teaId,tierId,position)
  SELECT id,boardId,teaId,tierId,position FROM `placements`;
DROP TABLE `placements`; ALTER TABLE `placements_new` RENAME TO `placements`;
CREATE UNIQUE INDEX `index_placements_boardId_teaId` ON `placements`(`boardId`,`teaId`);
CREATE INDEX `index_placements_teaId` ON `placements`(`teaId`);
CREATE INDEX `index_placements_tierId` ON `placements`(`tierId`);
-- repeat for tea_flavors (cols: teaId,dimension,intensity,position),
--            purchase_locations (id,teaId,position,kind,label,value),
--            tea_photos (id,teaId,uri,position,source,license,sourceUrl,createdAtEpochMs)
-- each with explicit column lists + FOREIGN KEY(teaId) REFERENCES tea_samples(id) ON DELETE CASCADE
-- and every index from 6.json recreated.

-- 5. Drop old root LAST.
DROP TABLE `teas`;

-- 6. Explicit FK sanity check (resolves finding #1).
PRAGMA foreign_key_check;
```

**Destructive-free guarantee:** no `DROP` of any user-data table until its data is copied into the replacement and verified by `identityHash` validation; everything is in Room's migration transaction; no `fallbackToDestructiveMigration` in release (`AppModule.kt:53-63`).

### 4.4 Enrichment FK pre-condition (resolves finding #14 — a data-loss path)

In v7 `catalogRefId` is a real FK. The migration only stubs refs for catalog ids **already on a v6 tea**. The **most common write path** — background enrichment resolving a custom sample to a brand-new catalog id (`applyEnrichmentPatch` → `patchEnrichment` writes `catalogTeaId`, `TeaDao.kt:268-273`) — would then fail the FK on the link `UPDATE`. **Therefore ref-stub creation must be a precondition of every `catalogRefId` write, not just `addTea`:** introduce one `linkSampleToRef(sampleId, refId, type)` helper that does `INSERT OR IGNORE INTO catalog_refs(id,type,fetchedAtEpochMs) VALUES(:refId,:type,0)` **inside the same `@Transaction` before** the link `UPDATE`. All link sites (enrichment patch, add flow, backup restore) call it.

---

## 5. P1-2 localized-name rule + display resolver

**Rule:** a sample is valid with **≥1 non-blank name in any locale** (not `ru` specifically). Names are child rows in `tea_sample_names(sampleId, locale∈{ru,en,pinyin,zh-Hans}, value, isPrimary, source)`. Migration guarantees the invariant for all v6 rows (v6 `nameRu` NOT NULL → every migrated sample has a name; the priority pass guarantees exactly one primary, even for blank-`ru` edge rows). Repo enforces ≥1 name and exactly one `isPrimary` on every insert/update.

**Display-name resolver** (deterministic, mirrors server `tea_name` + `CatalogTea.displayName`; used at **every** title site, never returns null):
1. The locale the user pinned for this sample (`tea_samples.displayNamePref`) if that name row exists.
2. The app/device locale's name row (`ru↔ru`, `zh↔zh-Hans`, else `en`).
3. The row with `isPrimary=1`.
4. Deterministic fallback by fixed priority `ru → en → pinyin → zh-Hans` (first present).

`secondaryName` = the remaining present names joined (`pinyin · 漢字`) minus the chosen primary, recomputed at each site (today it's `listOfNotNull(pinyin,nameZh).join(" · ")` on domain `Tea`).

**Dedup stays a non-blocking suggestion** (decision 2 + P1-2): a normalized-name match across name rows surfaces "looks like X — reuse?" but **never auto-merges**. Catalog-ref match is the only default reuse path, and even that is overridable by "add another".

---

## 6. Repo, UI, and backup/restore changes

### 6.1 `data/db`
- `Entities.kt`: `TeaEntity`→`TeaSampleEntity` (drop name* cols, `catalogTeaId`→`catalogRefId`, add `vendor/product/harvestYear/batch/grade/displayNamePref`, drop `UNIQUE`, add SET-NULL FK). New `TeaSampleNameEntity`, `CatalogRefEntity`. Child FK targets → `tea_samples`.
- `Relations.kt`: `SampleWithChildren` adds `@Relation names: List<TeaSampleNameEntity>` + optional `@Relation ref`. `PlacementWithSample` keeps the `.firstOrNull() ?: error(...)` path.
- `TeaDao.kt`: repoint every `FROM teas`/`teaId`/`catalogTeaId`. `loadTeaMatchKeys()` (`TeaDao.kt:68`) reads names from `tea_sample_names`. `findTeaIdByCatalogId`→`findSampleIdByCatalogRefId` (returns FIRST with a **stable `ORDER BY id`** — finding #7). `insertTeas`→`insertSamples`+`insertNames`. New `updateSampleNames` (delete+reinsert name rows). `teasNeedingEnrichment` (`TeaDao.kt:235`) reads **`tea_samples.enrichmentState` only**, never the ref's (finding #6/#24). `exportSnapshot`/`replaceAll`/`SeedEntities` gain `tea_sample_names` + `catalog_refs`.
- **`applyEnrichmentPatch` redesign (findings #14/#15/#16/#23):** the link is now **per-sample** — every sample that resolves to ref R links to R. **Remove the `linkOwner` branch** (`TeaDao.kt:268-272`, which existed only because the v6 `UNIQUE` made the link unsharable); each sample sets its own `catalogRefId` via `linkSampleToRef` (4.4). Catalog **names** must route per the open question Q2 (do not silently overwrite `source='user'` rows on refresh).
- `TeaDatabase.kt` → version 7, entity list updated. `AppModule.kt` adds `MIGRATION_6_7` to the builder (debug keeps destructive fallback for churn; **release runs the real migration** — `AppModule.kt:46-65`).

### 6.2 Repository (`TeaBoardRepository.kt`)
- `resolveTeaIdForMatch` (`TeaBoardRepository.kt:370-412`): the **catalog branch** reuses by `catalogRefId` **only as an explicit reuse intent**, not a silent reuse inside `addTea` (findings #16/#23). The **name branch** becomes a non-blocking suggestion — **custom add always creates a new sample** (finding #17; today the name branch auto-reuses and drops the 2nd add's fields).
- New `addAnotherSample` path that bypasses reuse and creates a 2nd sample on the same `catalogRefId` with independent children (P1-1 acceptance).
- `addTea`: insert sample + name rows (+ ref stub via `linkSampleToRef` when linking a never-seen ref).
- `updateTea`: also rewrite name rows.
- **Catalog refresh writer (co-requisite, finding #5/#22/#25):** upsert `catalog_refs` by id, overwrite ref facts **only**, never touch `tea_samples`/names/flavors/notes/purchases/photos. Must ship **with** v7 (open question Q7) or stubs never populate.

### 6.3 UI
- **Validation (the P1-2 chokepoint):** `AddTeaForm.isValid` (`AddTeaModels.kt:88`) changes from `nameRu.isNotBlank()` to "≥1 of {ru,en,pinyin,zh} non-blank". `AddTeaViewModel.submit()` snackbar `add_tea_error_name_required` (`AddTeaViewModel.kt:387`) reworded from "русское поле обязательно" to "укажите хотя бы одно название"; `AddTeaScreen.kt:226-227` `isError=form.nameRu.isBlank()` + hint `field_name_ru_hint` ("Обязательное поле") move to "all four blank". No single field is required.
- **Display resolver at ALL `nameRu` read sites — 7+ confirmed by grep, not 4 (finding #20):** `TeaCard.kt:84`, `BoardScreen.kt:311` (FeaturedTea/DraggableTeaCard), `MyTeasScreen.kt:179`, `TeaDetailScreen.kt:86` (title bar), `:145` (a11y/share name), `:181` (headline), **plus the two delete-confirm dialogs `BoardScreen.kt:665` and `AddTeaScreen.kt:402`**. Missing any site renders a blank title for a non-`ru`-only sample (invisible in upgrade QA because migrated rows keep a `ru` primary). `MyTeasModels.kt:48` sort and `filterMyTeas` substring search must use the resolved name set across all locale rows.
- **`pickCatalogTea` locale fix (finding #21):** `AddTeaViewModel.kt:312` currently writes `nameRu = tea.nameRu ?: tea.displayName`, so an en-only catalog tea lands its English string in the `ru` field. Rework to write each catalog name into its **correct locale row** and set `displayNamePref`/`isPrimary` deterministically.
- **New sample fields** (`vendor/product/harvestYear/batch/grade`): net-new form rows in `AddTeaScreen`, persisted in **both** add and edit modes (unlike transient `sourceText`), surfaced as a disambiguator subtitle on cards and a detail block — **critical** because two samples of one ref otherwise render identical titles. `toTea()`/`toForm()` carry them.
- **Strings:** add ru/en/zh for the any-name validation, the new field labels, and an "add another sample of this tea" action. Release gate "user-visible ru/en/zh copy complete for changed flows" applies.

### 6.4 Backup / restore (a hard rollout blocker — findings #9/#13/#19)
Today `BackupBundle` is `formatVersion=1`, a flat 1:1 dump of the `teas` table with scalar name columns and `catalogTeaId` per tea (`BackupModels.kt:24,54-69,121-189`); `BackupTea.nameRu` is **non-null**. `replaceAll` is destructive (`deleteAllBoards()`+`deleteAllTeas()` then reseed, `TeaDao.kt:351-364`) and never touches `catalog_refs`.

Required, shipped **with** v7:
- Bump `BACKUP_FORMAT_VERSION` → 2; define v7 DTOs (`sampleNames[]`, `catalogRefs[]`); make `BackupTea`'s name field nullable / move names to `sampleNames[]` so a v7 only-`zh` sample round-trips.
- **v6→v7 bundle up-converter** mirroring the SQL fan-out exactly: every non-blank `nameRu/En/pinyin/Zh` → a `BackupSampleName` row with the same priority-based `isPrimary`; each DISTINCT non-null `catalogTeaId` → a stub `catalog_ref`.
- **Add `catalog_refs` to `SeedEntities` + `replaceAll`** and order inserts so refs precede samples (else restoring any linked tea fails the new FK — finding #13).
- **Make `replaceAll` validate-before-wipe:** parse + up-convert + validate the full graph, then delete+seed in one transaction that rolls back on any failure, so a bad/old bundle leaves the live DB intact (finding #9). These are pure mapping → testable in the **JVM** test set without an emulator.

---

## 7. MigrationTestHelper v6→v7 plan + CI gate

**Current state (verified):** no `app/src/androidTest` source set, no `androidx.room:room-testing`, no Robolectric. `ci.yml` `app` job runs only `./gradlew check assembleDebug` (no emulator). `release.yml` runs only `assembleRelease` — **it does not run `check`, so it skips all JVM tests too (P1-4).** So today the lossless guarantee would be asserted by a test that never runs.

**Test infra (net-new, must land before the migration PR):**
1. Add `androidx.room:room-testing` (androidTest), `androidx.test:core/runner/rules` to `app/gradle/libs.versions.toml`; wire the schemas dir as an androidTest asset: `sourceSets.androidTest.assets.srcDir("$projectDir/schemas")` so `MigrationTestHelper` finds `6.json`/`7.json`.
2. Build once → KSP writes `app/schemas/.../7.json` → **commit it** (the diff contract).
3. **`MigrationTeaSampleSplitTest` (androidTest)** — recommended **Gradle Managed Device** (real SQLite/FK semantics; Robolectric can mock FK behavior incorrectly):
   - `createDatabase(TEST_DB, 6)`; raw-SQL v6 fixture: a board+tiers; a catalog-linked tea (`catalogTeaId=42`, all 4 names, flavors, 2 purchases, 1 photo, placements on **2 boards** — exercises #42); a **custom** tea (`catalogTeaId NULL`, only `nameRu`); a **`nameRu`+only `nameZh`** tea; a **blank-but-non-null `nameRu`** tea (finding #4); a **zero-placement** pool tea (finding #11); a purchase at a **non-zero position** (finding #11/purchase id stability).
   - `runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)` — `validateDroppedTables=true`; asserts `identityHash` matches `7.json` (catches any missing index/FK).
   - Open the real Room DB and assert losslessness: `tea_samples.id == old teas.id`; `catalogRefId == old catalogTeaId`; one `catalog_refs` stub for id 42; name rows fanned out; **exactly one `isPrimary=1` per sample** even for the blank-`ru` row; every placement/flavor/purchase/photo row count + FK preserved across both boards; photo `uri`/`id` unchanged; purchase id `== $id-p$pos`; **every migrated `sample.type == old teas.type`** (no silent GREEN, finding #12); **`PRAGMA foreign_key_check` returns no rows.**
4. **Repo smoke (androidTest, on the migrated DB):** `observeBoards()` yields both boards sharing the sample; `addTea` reuses by `catalogRefId` only on explicit intent; **`addAnotherSample` creates a 2nd sample on ref 42 with independent notes/flavor/purchases/photos** (P1-1 acceptance); two same-named **custom** adds → two samples, both children intact (finding #17); enrichment of two samples on one ref → **both** carry `catalogRefId` (findings #16/#23); `deleteTea` cascades; SET-NULL on `catalog_refs` delete leaves the sample; `teasNeedingEnrichment` does **not** return a DONE sample whose ref is PENDING (finding #24).
5. **Backup round-trip (JVM, no emulator):** export v7 → import → assert equality; frozen-v6-bundle import → assert up-convert fan-out; bad-bundle restore leaves live DB intact.

**CI gate (net-new):** add an androidTest job to `ci.yml` (Gradle Managed Device, e.g. `pixel6Api34DebugAndroidTest`, or `connectedDebugAndroidTest`) running at least `MigrationTeaSampleSplitTest` + the repo smoke; make it a **required PR check**. Make `release.yml` depend on `check` + the migration androidTest (fix P1-4 — it currently builds the APK with no tests). Add a `git diff --exit-code app/schemas` gate so an uncommitted `7.json` fails the build. **Acceptance (P0-1 §9):** a DB created by v0.1.0 (v6) upgrades to v7 with every record + photo reference preserved, using the checked-in `6.json`.

---

## 8. Risks + open questions

### Risks (all adversarial data-loss/high findings folded into §4/§6/§7)
- **R1 — Dropping `UNIQUE(catalogTeaId)`** removes the DB backstop against wrong-merge; the reuse/no-merge contract now lives entirely in repo code (`resolveTeaIdForMatch` + `addAnotherSample`). Intentional (P1-1 + decision 2); covered by repo tests, not a constraint (findings #1-risk/#7).
- **R2 — `identityHash` fragility:** hand-authored DDL that omits a Room default (e.g. `enrichmentState DEFAULT 'NONE'`) or any index fails validation → release crash-loop. Mitigation: paste `createSql` from generated `7.json` (finding #2).
- **R3 — `SELECT *` corruption:** positional column mapping silently corrupts child rows on any reorder; `tea_photos` worst (path lands in `createdAtEpochMs`, breaks display + `reconcile()`). Mitigation: explicit column lists everywhere (findings #3/#8).
- **R4 — Photo file loss:** `reconcile()` deletes on-disk files not referenced by a `tea_photos` row; a lost/garbled row → permanent image loss on next launch. Mitigation: explicit-column copy, id-set assertion, stable photo ids (finding #8).
- **R5 — Backup lockstep:** new tables + nullable name break the flat-`teas` serializer; an old/buggy bundle restored after a destructive `replaceAll` loses both copies. Mitigation: format v2 + up-converter + validate-before-wipe, all in JVM tests (findings #9/#13/#19).
- **R6 — Stub refs never populate** if the catalog-refresh writer ships later than v7 (a DONE sample is never re-dispatched). Mitigation: ship the refresh writer **in the same milestone**, or set migrated linked samples to `enrichmentState='PENDING'` (findings #5/#22/#25).
- **R7 — Display regression** for new only-`en`/only-`zh` samples if any of the 7+ title sites is missed; masked in upgrade QA because migrated rows keep `ru` primary. Mitigation: land resolver at all sites + `isValid` change together + explicit only-`zh`/only-`en` add+display+delete-dialog tests (findings #18/#20).
- **R8 — CI gate is net-new infra**, multiple PRs of work; without it the lossless guarantee is unverified and P0-1's acceptance is not actually met (findings #21-CI/#26).
- **R9 — `enrichmentState` in two places** (sample vs ref); conflating them re-dispatches on server state and loops. Mitigation: `teasNeedingEnrichment` strictly on `tea_samples.enrichmentState` + a test (findings #6/#24).

### Open questions (unresolved decisions the owner should rule on)
- **Q1 (shortBlurb home):** keep `shortBlurb` (and `type`/`origin` when linked) on `tea_samples`, or move to `catalog_refs`? On the sample is simpler for v7 and keeps enrichment working, but a refresh can't update the cached blurb and a custom sample carries link-only columns. Affects whether a 2nd migration is needed (findings #7/#21).
- **Q2 (catalog names vs personal names — a HIGH finding, finding #15):** if enrichment writes catalog names into `tea_sample_names`, refresh can overwrite a user-typed row. **Decide:** (a) catalog names live ONLY on `catalog_refs` and the resolver falls back to ref names (refresh touches ref rows only), or (b) seed personal rows marked `source='catalog'` and make refresh touch **only** `source='catalog'` rows, never `source='user'`, never delete+reinsert across the whole locale set. Must be answered before implementing the enrichment-path rework.
- **Q3 (custom "add another"):** does the explicit escape also apply when re-adding a CUSTOM (no-ref) sample whose name normalizes to an existing one? Spec: custom default is ALWAYS create-new (never silent reuse). Confirm the suggestion UI exists.
- **Q4 (name aliases):** is one-per-locale (`PK(sampleId,locale)`) sufficient for personal names, or do users need multiple aliases per locale (forcing a surrogate id + `UNIQUE(sampleId,locale,value)`)?
- **Q5 (cosmetic renames):** accept `placements.teaId` meaning `sampleId` (and table names `placements`/`purchase_locations`), or pay the extra rebuild now to rename to `sampleId`/`BoardPlacement`/`Purchase` while already rewriting these tables?
- **Q6 (`isPrimary` enforcement):** repo-enforced invariant (proposed, portable) vs a partial unique index `WHERE isPrimary=1` like the server — confirm Room 2.8.4 schema export/validation supports partial indexes before relying on one in the v7 contract.
- **Q7 (refresh writer co-requisite):** does a catalog-refresh-by-id writer exist anywhere, or is it net-new? If net-new it is a hard co-requisite that must ship with v7 so backfilled stubs ever populate (findings #5/#22/#25). Also reconsider whether catalog-derived columns currently on `teas` (`shortBlurb`, and for linked teas `nameZh`/`pinyin`/`origin`/`type`) should be **copied into the stub** at migrate time so the cached reference survives until first refresh, rather than stranded.
- **Q8 (CI device matrix):** Gradle Managed Device (faithful, slower) vs Robolectric for the required gate, and on which API level(s)?

