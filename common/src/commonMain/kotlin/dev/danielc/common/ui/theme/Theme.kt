package dev.danielc.common.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
fun FudgeRippleConfig(fg: Color = Color.White): RippleConfiguration {
    return RippleConfiguration(color = fg, rippleAlpha = RippleAlpha(
        0.16f,
        0.1f,
        0.08f,
        0.4f
    ))
}

@Composable
fun errorButtonColors(): ButtonColors {
    return ButtonColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(0.5f),
        disabledContentColor = MaterialTheme.colorScheme.onErrorContainer.copy(0.5f)
    )
}
@Composable
fun errorIconButtonColors(): IconButtonColors {
    return IconButtonColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
        disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
}
@Composable
fun primaryIconButtonColors(opacity: Float = 1f, opacityDisabled: Float = 0.8f): IconButtonColors {
    return IconButtonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(opacity),
        contentColor = MaterialTheme.colorScheme.onPrimary.copy(opacity),
        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(opacityDisabled),
        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(opacityDisabled),
    )
}
@Composable
fun secondaryIconButtonColors(opacity: Float = 1f, opacityDisabled: Float = 0.8f): IconButtonColors {
    return IconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondary.copy(opacity),
        contentColor = MaterialTheme.colorScheme.onSecondary.copy(opacity),
        disabledContainerColor = MaterialTheme.colorScheme.secondary.copy(opacityDisabled),
        disabledContentColor = MaterialTheme.colorScheme.onSecondary.copy(opacityDisabled),
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun FudgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        // Dark/Light switch only available Android 10+
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> DarkColorScheme
        else -> LightColorScheme
    }

    return MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}