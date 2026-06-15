package com.macsia.teatiers.domain.model

import androidx.annotation.StringRes
import com.macsia.teatiers.R

/**
 * Flavor-profile axes (decisions.md #23). Stable, extensible enum; intensities are
 * stored 0..5. Labels (горечь / bitterness / 苦味) are localized client-side, keyed by
 * the enum name, never stored in the DB.
 */
enum class FlavorDimension(@StringRes val labelRes: Int) {
    BITTERNESS(R.string.flavor_bitterness),
    SWEETNESS(R.string.flavor_sweetness),
    ASTRINGENCY(R.string.flavor_astringency),
    FRUITINESS(R.string.flavor_fruitiness),
    FLORAL(R.string.flavor_floral),
    GRASSY(R.string.flavor_grassy),
    SPICY(R.string.flavor_spicy),
    SMOKY(R.string.flavor_smoky),
    EARTHY_NUTTY(R.string.flavor_earthy_nutty),
    UMAMI(R.string.flavor_umami),
    ROASTED(R.string.flavor_roasted),
}
