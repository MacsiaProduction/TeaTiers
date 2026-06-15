package com.macsia.teatiers.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.macsia.teatiers.R

// Golos Text (SIL OFL 1.1) — a contemporary Cyrillic-first grotesque that also covers Latin, so
// the ru-first UI and pinyin share one voice. It ships as a single variable font, so each weight
// is an instance of the `wght` axis (needs API 26+, which matches our minSdk). Hanzi has no glyphs
// here and falls back to the platform CJK font automatically. License + attribution:
// app/src/main/assets/licenses/GolosText-OFL.txt.
private val GolosText = FontFamily(
    Font(R.font.golos_text, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.golos_text, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.golos_text, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.golos_text, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val DisplayFont = GolosText
private val BodyFont = GolosText

// Start from the Material defaults so every role (display/headline/label) carries Golos, then
// tune the roles the UI leans on. Headlines keep the default scale; titles/body are tightened.
private val Default = Typography()

val TeaTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontFamily = DisplayFont),
    displayMedium = Default.displayMedium.copy(fontFamily = DisplayFont),
    displaySmall = Default.displaySmall.copy(fontFamily = DisplayFont),
    headlineLarge = Default.headlineLarge.copy(fontFamily = DisplayFont),
    headlineMedium = Default.headlineMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = Default.labelLarge.copy(fontFamily = BodyFont),
    labelMedium = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)
