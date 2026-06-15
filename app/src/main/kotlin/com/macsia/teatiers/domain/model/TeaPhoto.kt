package com.macsia.teatiers.domain.model

/**
 * A photo attached to a user-tea (decisions.md #43; supersedes #24's single-photo line).
 *
 * - [uri] is the storage handle: an absolute file path under the app's private dir for [PhotoSource.USER]
 *   (bytes are copied in by `PhotoStore`, so the URI keeps working through gallery-cleanup and
 *   makes export/import in #26 a plain file copy). Future [PhotoSource.CATALOG] photos will store
 *   an HTTPS URL here instead.
 * - [position] is the per-tea contiguous order (0..n); reorder rewrites every row in one
 *   transaction (mirrors the tier/placement reordering pattern, decisions.md #38/#39).
 * - [license] / [sourceUrl] are intentionally nullable so the same row shape covers user uploads
 *   (no license needed) and a future CC catalog image with attribution. They stay null in MVP.
 */
data class TeaPhoto(
    val id: String,
    val uri: String,
    val position: Int,
    val source: PhotoSource = PhotoSource.USER,
    val license: String? = null,
    val sourceUrl: String? = null,
)

enum class PhotoSource { USER, CATALOG }
