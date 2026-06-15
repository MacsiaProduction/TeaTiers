package com.macsia.teatiers.data.sample

import com.macsia.teatiers.domain.model.Board
import com.macsia.teatiers.domain.model.FlavorDimension.ASTRINGENCY
import com.macsia.teatiers.domain.model.FlavorDimension.BITTERNESS
import com.macsia.teatiers.domain.model.FlavorDimension.EARTHY_NUTTY
import com.macsia.teatiers.domain.model.FlavorDimension.FLORAL
import com.macsia.teatiers.domain.model.FlavorDimension.FRUITINESS
import com.macsia.teatiers.domain.model.FlavorDimension.GRASSY
import com.macsia.teatiers.domain.model.FlavorDimension.ROASTED
import com.macsia.teatiers.domain.model.FlavorDimension.SMOKY
import com.macsia.teatiers.domain.model.FlavorDimension.SWEETNESS
import com.macsia.teatiers.domain.model.FlavorDimension.UMAMI
import com.macsia.teatiers.domain.model.FlavorScore
import com.macsia.teatiers.domain.model.PurchaseLocation
import com.macsia.teatiers.domain.model.Tea
import com.macsia.teatiers.domain.model.TeaType
import com.macsia.teatiers.domain.model.Tier
import javax.inject.Inject

/**
 * Demo content used to seed the Room store on first run (M1) so the screens open with real,
 * multilingual teas until the catalog API (M2/M4) exists. Pure data, no I/O.
 */
class SampleBoardProvider @Inject constructor() {

    fun boards(): List<Board> = listOf(favorites(), oolongs(), tasting())

    private fun defaultTiers(): List<Tier> = listOf(
        Tier(id = "s", label = "S", position = 0),
        Tier(id = "a", label = "A", position = 1),
        Tier(id = "b", label = "B", position = 2),
        Tier(id = "c", label = "C", position = 3),
        Tier(id = "d", label = "D", position = 4),
    )

    private fun favorites(): Board = Board(
        id = "favorites",
        name = "Любимые чаи",
        tiers = defaultTiers(),
        placements = mapOf(
            "s" to listOf(daHongPao, longjing),
            "a" to listOf(tieguanyin, baiHaoYinZhen),
            "b" to listOf(dianHong),
            "c" to listOf(earlGrey),
            "d" to listOf(gunpowder),
        ),
        unranked = listOf(shuPuer),
    )

    private fun oolongs(): Board = Board(
        id = "oolongs",
        name = "Уишаньские улуны",
        tiers = defaultTiers(),
        placements = mapOf(
            "s" to listOf(daHongPao),
            "a" to listOf(tieguanyin),
        ),
        unranked = emptyList(),
    )

    private fun tasting(): Board = Board(
        id = "tasting",
        name = "Дегустация 2026",
        tiers = defaultTiers(),
        placements = mapOf(
            "s" to listOf(baiHaoYinZhen),
            "b" to listOf(dianHong, longjing),
        ),
        unranked = listOf(shuPuer, gunpowder),
    )

    // Flavor intensities are illustrative (0..5), tuned to make the radar/bars legible.
    private val daHongPao = Tea(
        id = "da-hong-pao",
        nameRu = "Да Хун Пао",
        nameZh = "大红袍",
        pinyin = "Dà Hóng Páo",
        nameEn = "Da Hong Pao",
        type = TeaType.OOLONG,
        origin = "Уишань, Фуцзянь",
        shortBlurb = "Сильно прожаренный утёсный улун с минеральной глубиной.",
        flavor = listOf(
            FlavorScore(ROASTED, 5),
            FlavorScore(EARTHY_NUTTY, 3),
            FlavorScore(SWEETNESS, 3),
            FlavorScore(FLORAL, 2),
        ),
        notes = "Брал на развес; раскрывается к третьему-четвёртому проливу.",
        purchaseLocations = listOf(
            PurchaseLocation.FreeText("Чайный рынок в Уишане", "Поездка 2025"),
            PurchaseLocation.Marketplace("https://example.com/da-hong-pao", "Тейстудия"),
        ),
    )

    private val longjing = Tea(
        id = "longjing",
        nameRu = "Лунцзин",
        nameZh = "龙井",
        pinyin = "Lóngjǐng",
        nameEn = "Dragon Well",
        type = TeaType.GREEN,
        origin = "Сиху, Чжэцзян",
        shortBlurb = "Плоский жареный зелёный чай с орехово-сладкой нотой.",
        flavor = listOf(
            FlavorScore(GRASSY, 4),
            FlavorScore(SWEETNESS, 3),
            FlavorScore(UMAMI, 3),
            FlavorScore(ASTRINGENCY, 1),
        ),
        purchaseLocations = listOf(
            PurchaseLocation.FreeText("Привезён другом из поездки в Ханчжоу"),
        ),
    )

    private val tieguanyin = Tea(
        id = "tieguanyin",
        nameRu = "Тегуаньинь",
        nameZh = "铁观音",
        pinyin = "Tiě Guānyīn",
        nameEn = "Iron Goddess",
        type = TeaType.OOLONG,
        origin = "Аньси, Фуцзянь",
        shortBlurb = "Светлый улун с ярким орхидейным ароматом.",
        flavor = listOf(
            FlavorScore(FLORAL, 5),
            FlavorScore(SWEETNESS, 3),
            FlavorScore(GRASSY, 2),
        ),
    )

    private val baiHaoYinZhen = Tea(
        id = "bai-hao-yin-zhen",
        nameRu = "Бай Хао Инь Чжэнь",
        nameZh = "白毫银针",
        pinyin = "Báiháo Yínzhēn",
        nameEn = "Silver Needle",
        type = TeaType.WHITE,
        origin = "Фудин, Фуцзянь",
        shortBlurb = "Серебряные почки, мягкий медово-цветочный настой.",
        flavor = listOf(
            FlavorScore(SWEETNESS, 3),
            FlavorScore(FLORAL, 3),
            FlavorScore(FRUITINESS, 2),
        ),
    )

    private val dianHong = Tea(
        id = "dian-hong",
        nameRu = "Дянь Хун",
        nameZh = "滇红",
        pinyin = "Diān Hóng",
        nameEn = "Yunnan Red",
        type = TeaType.BLACK,
        origin = "Юньнань",
        shortBlurb = "Золотистый красный чай, сладкий с нотой какао.",
        flavor = listOf(
            FlavorScore(SWEETNESS, 4),
            FlavorScore(FRUITINESS, 3),
            FlavorScore(ROASTED, 2),
        ),
    )

    private val earlGrey = Tea(
        id = "earl-grey",
        nameRu = "Эрл Грей",
        nameEn = "Earl Grey",
        type = TeaType.BLENDED,
        origin = "Купаж с бергамотом",
        shortBlurb = "Чёрный чай, ароматизированный бергамотом.",
        flavor = listOf(
            FlavorScore(FLORAL, 3),
            FlavorScore(ASTRINGENCY, 3),
            FlavorScore(BITTERNESS, 2),
        ),
    )

    private val gunpowder = Tea(
        id = "gunpowder",
        nameRu = "Ганпаудер",
        nameZh = "珠茶",
        pinyin = "Zhū Chá",
        nameEn = "Gunpowder",
        type = TeaType.GREEN,
        origin = "Чжэцзян",
        shortBlurb = "Скрученный в дробинки зелёный чай, дымный и резкий.",
        flavor = listOf(
            FlavorScore(ASTRINGENCY, 3),
            FlavorScore(SMOKY, 3),
            FlavorScore(BITTERNESS, 3),
        ),
    )

    private val shuPuer = Tea(
        id = "shu-puer",
        nameRu = "Шу Пуэр",
        nameZh = "熟普洱",
        pinyin = "Shú Pǔ'ěr",
        nameEn = "Ripe Pu-erh",
        type = TeaType.DARK,
        origin = "Юньнань",
        shortBlurb = "Постферментированный чай, землистый и густой.",
        flavor = listOf(
            FlavorScore(EARTHY_NUTTY, 4),
            FlavorScore(SWEETNESS, 3),
            FlavorScore(ROASTED, 2),
        ),
    )
}
