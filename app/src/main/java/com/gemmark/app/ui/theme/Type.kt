package com.gemmark.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.gemmark.app.R

/**
 * Brand typography:
 *  - Google Sans Flex — the app voice (all text styles)
 *  - Google Sans Code — the instrument voice (labels, numerals annotations,
 *    console, anything data-flavoured)
 * Static instances bundled from Google Fonts (OFL).
 */
val GoogleSansFlex = FontFamily(
    Font(R.font.google_sans_flex_400, FontWeight.Normal),
    Font(R.font.google_sans_flex_500, FontWeight.Medium),
    Font(R.font.google_sans_flex_600, FontWeight.SemiBold),
    Font(R.font.google_sans_flex_700, FontWeight.Bold),
)

val GoogleSansCode = FontFamily(
    Font(R.font.google_sans_code_400, FontWeight.Normal),
    Font(R.font.google_sans_code_500, FontWeight.Medium),
    Font(R.font.google_sans_code_700, FontWeight.Bold),
)

private val base = Typography()

val GemmarkTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = GoogleSansFlex),
    displayMedium = base.displayMedium.copy(fontFamily = GoogleSansFlex),
    displaySmall = base.displaySmall.copy(fontFamily = GoogleSansFlex),
    headlineLarge = base.headlineLarge.copy(fontFamily = GoogleSansFlex),
    headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSansFlex),
    headlineSmall = base.headlineSmall.copy(fontFamily = GoogleSansFlex),
    titleLarge = base.titleLarge.copy(fontFamily = GoogleSansFlex),
    titleMedium = base.titleMedium.copy(fontFamily = GoogleSansFlex),
    titleSmall = base.titleSmall.copy(fontFamily = GoogleSansFlex),
    bodyLarge = base.bodyLarge.copy(fontFamily = GoogleSansFlex),
    bodyMedium = base.bodyMedium.copy(fontFamily = GoogleSansFlex),
    bodySmall = base.bodySmall.copy(fontFamily = GoogleSansFlex),
    labelLarge = base.labelLarge.copy(fontFamily = GoogleSansFlex),
    // Small labels are the instrument voice — mono.
    labelMedium = base.labelMedium.copy(fontFamily = GoogleSansCode),
    labelSmall = base.labelSmall.copy(fontFamily = GoogleSansCode),
)

/** Big metric numeral, e.g. 34.2 in a stat card. */
val MetricValueStyle = TextStyle(
    fontFamily = GoogleSansFlex,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
)

/** Hero numeral in the progress ring. */
val MetricHeroStyle = TextStyle(
    fontFamily = GoogleSansFlex,
    fontWeight = FontWeight.Bold,
    fontSize = 44.sp,
    lineHeight = 48.sp,
)

/** Console text for the activity log. */
val ConsoleTextStyle = TextStyle(
    fontFamily = GoogleSansCode,
    fontSize = 12.sp,
    lineHeight = 18.sp,
)
