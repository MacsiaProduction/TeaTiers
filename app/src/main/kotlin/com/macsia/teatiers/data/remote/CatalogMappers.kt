package com.macsia.teatiers.data.remote

import com.macsia.teatiers.data.remote.dto.TeaNameDto
import com.macsia.teatiers.data.remote.dto.TeaSummaryDto
import com.macsia.teatiers.domain.model.CatalogName
import com.macsia.teatiers.domain.model.CatalogTea
import com.macsia.teatiers.domain.model.TeaType

/** Wire enum string -> app [TeaType]; an unknown/new server value folds to [TeaType.OTHER]. */
fun teaTypeFromWire(raw: String): TeaType =
    runCatching { TeaType.valueOf(raw) }.getOrDefault(TeaType.OTHER)

fun TeaNameDto.toDomain(): CatalogName =
    CatalogName(locale = locale, name = name, isPrimary = primary)

fun TeaSummaryDto.toDomain(): CatalogTea = CatalogTea(
    id = id,
    type = teaTypeFromWire(type),
    originCountry = originCountry,
    brand = brand,
    verificationStatus = verificationStatus,
    names = names.map { it.toDomain() },
)
