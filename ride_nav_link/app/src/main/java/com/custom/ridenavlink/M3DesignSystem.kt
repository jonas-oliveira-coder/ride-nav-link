package com.custom.ridenavlink

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

enum class M3TypeRole(val sizeSp: Float, val bold: Boolean, val family: String) {
    DISPLAY_NUMERAL(36f, true, "sans-serif-medium"),
    HEADLINE(22f, true, "sans-serif-medium"),
    TITLE_MEDIUM(16f, true, "sans-serif-medium"),
    TITLE_SMALL(14f, true, "sans-serif-medium"),
    BODY_MEDIUM(14f, false, "sans-serif"),
    BODY_SMALL(13f, false, "sans-serif"),
    LABEL_LARGE(14f, true, "sans-serif-medium"),
    LABEL_MEDIUM(12f, true, "sans-serif-medium")
}

object M3Shape {
    const val SMALL_DP = 8
    const val MEDIUM_DP = 12
    const val LARGE_DP = 16
    const val EXTRA_LARGE_DP = 28
    const val FULL_DP = 999
}

data class M3ColorScheme(
    val isDark: Boolean,
    val surface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val warning: Int,
    val warningContainer: Int,
    val onWarningContainer: Int,
    val rippleOnSurface: Int,
    val rippleOnPrimary: Int
)

object M3Theme {

    fun isDarkTheme(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun areAnimationsEnabled(context: Context): Boolean {
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }

    fun resolveColorScheme(context: Context): M3ColorScheme {
        val isDark = isDarkTheme(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                return buildApi34DynamicColorScheme(context, isDark)
            } catch (_: Exception) {
                // Fallback safely to API 31+ tonal scheme if any system token is unavailable
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return buildDynamicColorScheme(context, isDark)
            } catch (_: Exception) {
                // Fallback safely if OEM does not expose all system_accent/neutral resources
            }
        }
        return if (isDark) darkFallbackScheme() else lightFallbackScheme()
    }

    private fun buildApi34DynamicColorScheme(context: Context, isDark: Boolean): M3ColorScheme {
        fun sysColor(resId: Int): Int = context.getColor(resId)

        return if (isDark) {
            val onSurface = sysColor(android.R.color.system_on_surface_dark)
            val onPrimary = sysColor(android.R.color.system_on_primary_dark)
            M3ColorScheme(
                isDark = true,
                surface = sysColor(android.R.color.system_surface_dark),
                surfaceContainerLowest = sysColor(android.R.color.system_surface_container_lowest_dark),
                surfaceContainerLow = sysColor(android.R.color.system_surface_container_low_dark),
                surfaceContainer = sysColor(android.R.color.system_surface_container_dark),
                surfaceContainerHigh = sysColor(android.R.color.system_surface_container_high_dark),
                surfaceContainerHighest = sysColor(android.R.color.system_surface_container_highest_dark),
                onSurface = onSurface,
                onSurfaceVariant = sysColor(android.R.color.system_on_surface_variant_dark),
                primary = sysColor(android.R.color.system_primary_dark),
                onPrimary = onPrimary,
                primaryContainer = sysColor(android.R.color.system_primary_container_dark),
                onPrimaryContainer = sysColor(android.R.color.system_on_primary_container_dark),
                secondary = sysColor(android.R.color.system_secondary_dark),
                onSecondary = sysColor(android.R.color.system_on_secondary_dark),
                secondaryContainer = sysColor(android.R.color.system_secondary_container_dark),
                onSecondaryContainer = sysColor(android.R.color.system_on_secondary_container_dark),
                tertiary = sysColor(android.R.color.system_tertiary_dark),
                onTertiary = sysColor(android.R.color.system_on_tertiary_dark),
                tertiaryContainer = sysColor(android.R.color.system_tertiary_container_dark),
                onTertiaryContainer = sysColor(android.R.color.system_on_tertiary_container_dark),
                outline = sysColor(android.R.color.system_outline_dark),
                outlineVariant = sysColor(android.R.color.system_outline_variant_dark),
                error = sysColor(android.R.color.system_error_dark),
                onError = sysColor(android.R.color.system_on_error_dark),
                errorContainer = sysColor(android.R.color.system_error_container_dark),
                onErrorContainer = sysColor(android.R.color.system_on_error_container_dark),
                warning = Color.parseColor("#FFB95C"),
                warningContainer = Color.parseColor("#653D00"),
                onWarningContainer = Color.parseColor("#FFDCBA"),
                rippleOnSurface = withAlpha(onSurface, 0x1F),
                rippleOnPrimary = withAlpha(onPrimary, 0x24)
            )
        } else {
            val onSurface = sysColor(android.R.color.system_on_surface_light)
            val onPrimary = sysColor(android.R.color.system_on_primary_light)
            M3ColorScheme(
                isDark = false,
                surface = sysColor(android.R.color.system_surface_light),
                surfaceContainerLowest = sysColor(android.R.color.system_surface_container_lowest_light),
                surfaceContainerLow = sysColor(android.R.color.system_surface_container_low_light),
                surfaceContainer = sysColor(android.R.color.system_surface_container_light),
                surfaceContainerHigh = sysColor(android.R.color.system_surface_container_high_light),
                surfaceContainerHighest = sysColor(android.R.color.system_surface_container_highest_light),
                onSurface = onSurface,
                onSurfaceVariant = sysColor(android.R.color.system_on_surface_variant_light),
                primary = sysColor(android.R.color.system_primary_light),
                onPrimary = onPrimary,
                primaryContainer = sysColor(android.R.color.system_primary_container_light),
                onPrimaryContainer = sysColor(android.R.color.system_on_primary_container_light),
                secondary = sysColor(android.R.color.system_secondary_light),
                onSecondary = sysColor(android.R.color.system_on_secondary_light),
                secondaryContainer = sysColor(android.R.color.system_secondary_container_light),
                onSecondaryContainer = sysColor(android.R.color.system_on_secondary_container_light),
                tertiary = sysColor(android.R.color.system_tertiary_light),
                onTertiary = sysColor(android.R.color.system_on_tertiary_light),
                tertiaryContainer = sysColor(android.R.color.system_tertiary_container_light),
                onTertiaryContainer = sysColor(android.R.color.system_on_tertiary_container_light),
                outline = sysColor(android.R.color.system_outline_light),
                outlineVariant = sysColor(android.R.color.system_outline_variant_light),
                error = sysColor(android.R.color.system_error_light),
                onError = sysColor(android.R.color.system_on_error_light),
                errorContainer = sysColor(android.R.color.system_error_container_light),
                onErrorContainer = sysColor(android.R.color.system_on_error_container_light),
                warning = Color.parseColor("#8A5300"),
                warningContainer = Color.parseColor("#FFE0B2"),
                onWarningContainer = Color.parseColor("#2E1500"),
                rippleOnSurface = withAlpha(onSurface, 0x1A),
                rippleOnPrimary = withAlpha(onPrimary, 0x24)
            )
        }
    }

    private fun buildDynamicColorScheme(context: Context, isDark: Boolean): M3ColorScheme {
        fun sysColor(resId: Int): Int = context.getColor(resId)

        return if (isDark) {
            val neutral900 = sysColor(android.R.color.system_neutral1_900)
            val neutral800 = sysColor(android.R.color.system_neutral1_800)
            val neutral700 = sysColor(android.R.color.system_neutral1_700)
            val neutral1000 = sysColor(android.R.color.system_neutral1_1000)
            val onSurface = sysColor(android.R.color.system_neutral1_100)
            val onPrimary = sysColor(android.R.color.system_accent1_800)

            M3ColorScheme(
                isDark = true,
                surface = blendColors(neutral900, neutral1000, 0.15f),
                surfaceContainerLowest = blendColors(neutral900, neutral1000, 0.45f),
                surfaceContainerLow = blendColors(neutral900, neutral800, 0.22f),
                surfaceContainer = blendColors(neutral900, neutral800, 0.45f),
                surfaceContainerHigh = blendColors(neutral900, neutral800, 0.72f),
                surfaceContainerHighest = blendColors(neutral800, neutral700, 0.25f),
                onSurface = onSurface,
                onSurfaceVariant = sysColor(android.R.color.system_neutral2_200),
                primary = sysColor(android.R.color.system_accent1_200),
                onPrimary = onPrimary,
                primaryContainer = sysColor(android.R.color.system_accent1_700),
                onPrimaryContainer = sysColor(android.R.color.system_accent1_100),
                secondary = sysColor(android.R.color.system_accent2_200),
                onSecondary = sysColor(android.R.color.system_accent2_800),
                secondaryContainer = sysColor(android.R.color.system_accent2_700),
                onSecondaryContainer = sysColor(android.R.color.system_accent2_100),
                tertiary = sysColor(android.R.color.system_accent3_200),
                onTertiary = sysColor(android.R.color.system_accent3_800),
                tertiaryContainer = sysColor(android.R.color.system_accent3_700),
                onTertiaryContainer = sysColor(android.R.color.system_accent3_100),
                outline = sysColor(android.R.color.system_neutral2_400),
                outlineVariant = sysColor(android.R.color.system_neutral2_700),
                error = Color.parseColor("#F2B8B5"),
                onError = Color.parseColor("#601410"),
                errorContainer = Color.parseColor("#8C1D18"),
                onErrorContainer = Color.parseColor("#F9DEDC"),
                warning = Color.parseColor("#FFB95C"),
                warningContainer = Color.parseColor("#653D00"),
                onWarningContainer = Color.parseColor("#FFDCBA"),
                rippleOnSurface = withAlpha(onSurface, 0x1F),
                rippleOnPrimary = withAlpha(onPrimary, 0x24)
            )
        } else {
            val neutral10 = sysColor(android.R.color.system_neutral1_10)
            val neutral0 = sysColor(android.R.color.system_neutral1_0)
            val neutral100 = sysColor(android.R.color.system_neutral1_100)
            val onSurface = sysColor(android.R.color.system_neutral1_900)
            val onPrimary = neutral0

            M3ColorScheme(
                isDark = false,
                surface = neutral10,
                surfaceContainerLowest = neutral0,
                surfaceContainerLow = blendColors(neutral10, neutral100, 0.35f),
                surfaceContainer = blendColors(neutral10, neutral100, 0.58f),
                surfaceContainerHigh = blendColors(neutral10, neutral100, 0.80f),
                surfaceContainerHighest = neutral100,
                onSurface = onSurface,
                onSurfaceVariant = sysColor(android.R.color.system_neutral2_700),
                primary = sysColor(android.R.color.system_accent1_600),
                onPrimary = onPrimary,
                primaryContainer = sysColor(android.R.color.system_accent1_100),
                onPrimaryContainer = sysColor(android.R.color.system_accent1_900),
                secondary = sysColor(android.R.color.system_accent2_600),
                onSecondary = neutral0,
                secondaryContainer = sysColor(android.R.color.system_accent2_100),
                onSecondaryContainer = sysColor(android.R.color.system_accent2_900),
                tertiary = sysColor(android.R.color.system_accent3_600),
                onTertiary = neutral0,
                tertiaryContainer = sysColor(android.R.color.system_accent3_100),
                onTertiaryContainer = sysColor(android.R.color.system_accent3_900),
                outline = sysColor(android.R.color.system_neutral2_500),
                outlineVariant = sysColor(android.R.color.system_neutral2_200),
                error = Color.parseColor("#B3261E"),
                onError = Color.WHITE,
                errorContainer = Color.parseColor("#F9DEDC"),
                onErrorContainer = Color.parseColor("#410E0B"),
                warning = Color.parseColor("#8A5300"),
                warningContainer = Color.parseColor("#FFE0B2"),
                onWarningContainer = Color.parseColor("#2E1500"),
                rippleOnSurface = withAlpha(onSurface, 0x1A),
                rippleOnPrimary = withAlpha(onPrimary, 0x24)
            )
        }
    }

    private fun lightFallbackScheme(): M3ColorScheme = M3ColorScheme(
        isDark = false,
        surface = Color.parseColor("#F8F9FE"),
        surfaceContainerLowest = Color.parseColor("#FFFFFF"),
        surfaceContainerLow = Color.parseColor("#F1F3FA"),
        surfaceContainer = Color.parseColor("#ECEEF6"),
        surfaceContainerHigh = Color.parseColor("#E6E8F0"),
        surfaceContainerHighest = Color.parseColor("#E0E2EC"),
        onSurface = Color.parseColor("#191C22"),
        onSurfaceVariant = Color.parseColor("#434750"),
        primary = Color.parseColor("#0B57D0"),
        onPrimary = Color.parseColor("#FFFFFF"),
        primaryContainer = Color.parseColor("#D3E3FD"),
        onPrimaryContainer = Color.parseColor("#041E49"),
        secondary = Color.parseColor("#4E5D78"),
        onSecondary = Color.parseColor("#FFFFFF"),
        secondaryContainer = Color.parseColor("#D7E2F9"),
        onSecondaryContainer = Color.parseColor("#121C2E"),
        tertiary = Color.parseColor("#006A60"),
        onTertiary = Color.parseColor("#FFFFFF"),
        tertiaryContainer = Color.parseColor("#A8EFE4"),
        onTertiaryContainer = Color.parseColor("#00201C"),
        outline = Color.parseColor("#747782"),
        outlineVariant = Color.parseColor("#C4C6D2"),
        error = Color.parseColor("#B3261E"),
        onError = Color.parseColor("#FFFFFF"),
        errorContainer = Color.parseColor("#F9DEDC"),
        onErrorContainer = Color.parseColor("#410E0B"),
        warning = Color.parseColor("#8A5300"),
        warningContainer = Color.parseColor("#FFE0B2"),
        onWarningContainer = Color.parseColor("#2E1500"),
        rippleOnSurface = Color.parseColor("#1A191C22"),
        rippleOnPrimary = Color.parseColor("#24FFFFFF")
    )

    private fun darkFallbackScheme(): M3ColorScheme = M3ColorScheme(
        isDark = true,
        surface = Color.parseColor("#16181F"),
        surfaceContainerLowest = Color.parseColor("#101218"),
        surfaceContainerLow = Color.parseColor("#1E2129"),
        surfaceContainer = Color.parseColor("#232630"),
        surfaceContainerHigh = Color.parseColor("#2B2E39"),
        surfaceContainerHighest = Color.parseColor("#363A46"),
        onSurface = Color.parseColor("#E3E2EA"),
        onSurfaceVariant = Color.parseColor("#C4C6D2"),
        primary = Color.parseColor("#A8C7FA"),
        onPrimary = Color.parseColor("#062E6F"),
        primaryContainer = Color.parseColor("#0842A0"),
        onPrimaryContainer = Color.parseColor("#D3E3FD"),
        secondary = Color.parseColor("#BDC7DC"),
        onSecondary = Color.parseColor("#273141"),
        secondaryContainer = Color.parseColor("#3D4759"),
        onSecondaryContainer = Color.parseColor("#D9E3F8"),
        tertiary = Color.parseColor("#7CD8CB"),
        onTertiary = Color.parseColor("#003731"),
        tertiaryContainer = Color.parseColor("#005048"),
        onTertiaryContainer = Color.parseColor("#A8EFE4"),
        outline = Color.parseColor("#8E909C"),
        outlineVariant = Color.parseColor("#434750"),
        error = Color.parseColor("#F2B8B5"),
        onError = Color.parseColor("#601410"),
        errorContainer = Color.parseColor("#8C1D18"),
        onErrorContainer = Color.parseColor("#F9DEDC"),
        warning = Color.parseColor("#FFB95C"),
        warningContainer = Color.parseColor("#653D00"),
        onWarningContainer = Color.parseColor("#FFDCBA"),
        rippleOnSurface = Color.parseColor("#1FE3E2EA"),
        rippleOnPrimary = Color.parseColor("#24062E6F")
    )

    fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(from) * inv + Color.red(to) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(from) * inv + Color.green(to) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(from) * inv + Color.blue(to) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun applyRole(tv: TextView, role: M3TypeRole, color: Int? = null) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, role.sizeSp)
        tv.typeface = Typeface.create(role.family, if (role.bold) Typeface.BOLD else Typeface.NORMAL)
        if (color != null) {
            tv.setTextColor(color)
        }
    }

    // Typography roles (scalable sp units)
    fun applyType(tv: TextView, sizeSp: Float, bold: Boolean = false, color: Int? = null) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        val family = if (bold) "sans-serif-medium" else "sans-serif"
        tv.typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        if (color != null) {
            tv.setTextColor(color)
        }
    }

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun roundedSurface(
        bgColor: Int,
        radiusPx: Float,
        strokeColor: Int = 0,
        strokeWidthPx: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radiusPx
            if (strokeWidthPx > 0 && strokeColor != 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    fun interactiveSurface(
        context: Context,
        bgColor: Int,
        radiusPx: Float,
        rippleColor: Int,
        focusStrokeColor: Int,
        normalStrokeColor: Int = 0,
        normalStrokeWidthPx: Int = 0
    ): Drawable {
        val normal = roundedSurface(bgColor, radiusPx, normalStrokeColor, normalStrokeWidthPx)
        val focused = roundedSurface(bgColor, radiusPx, focusStrokeColor, dp(context, 2))
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), states, roundedSurface(Color.WHITE, radiusPx))
    }

    fun textFieldSurface(context: Context, scheme: M3ColorScheme, radiusPx: Float): Drawable {
        val normal = roundedSurface(
            bgColor = Color.TRANSPARENT,
            radiusPx = radiusPx,
            strokeColor = scheme.outline,
            strokeWidthPx = dp(context, 1)
        )
        val focused = roundedSurface(
            bgColor = Color.TRANSPARENT,
            radiusPx = radiusPx,
            strokeColor = scheme.primary,
            strokeWidthPx = dp(context, 2)
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_selected), focused)
            addState(intArrayOf(), normal)
        }
    }

    fun makeTintedIcon(context: Context, resId: Int, sizeDp: Int, tintColor: Int, decorative: Boolean = true): ImageView {
        val sizePx = dp(context, sizeDp)
        return ImageView(context).apply {
            setImageResource(resId)
            imageTintList = ColorStateList.valueOf(tintColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            if (decorative) {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
    }

    fun markAccessibilityHeading(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.isAccessibilityHeading = true
        }
    }
}
