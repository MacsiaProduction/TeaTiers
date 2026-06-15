package com.macsia.teatiers.domain.model

import androidx.annotation.StringRes
import com.macsia.teatiers.R

/**
 * Tea categories. Stable keys follow the GB/T 30766-2014 six-category system
 * (decisions.md #10) plus puer/herbal/blended/other. The catalog/DB stores the enum
 * name; user-facing labels are localized in strings.xml (decisions.md #23).
 *
 * The ru labels intentionally follow Chinese-tea convention: 红茶 -> «Красный» (BLACK),
 * 黑茶 -> «Чёрный» (DARK). The enum name is the stable English key, not the ru label.
 */
enum class TeaType(@StringRes val labelRes: Int) {
    GREEN(R.string.tea_type_green),
    WHITE(R.string.tea_type_white),
    YELLOW(R.string.tea_type_yellow),
    OOLONG(R.string.tea_type_oolong),
    BLACK(R.string.tea_type_black),
    DARK(R.string.tea_type_dark),
    PUER(R.string.tea_type_puer),
    HERBAL(R.string.tea_type_herbal),
    BLENDED(R.string.tea_type_blended),
    OTHER(R.string.tea_type_other),
}
