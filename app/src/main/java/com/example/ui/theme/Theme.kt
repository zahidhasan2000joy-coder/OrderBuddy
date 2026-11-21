package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PaircartColorScheme = lightColorScheme(
  primary = OrderGreen,
  onPrimary = Color.White,
  primaryContainer = OrderGreenLight,
  onPrimaryContainer = OrderGreenDark,
  secondary = OrderGreenAccent,
  onSecondary = Color.White,
  background = BackgroundGray,
  onBackground = TextDark,
  surface = BodyWhite,
  onSurface = TextDark,
  surfaceVariant = BackgroundGray,
  onSurfaceVariant = TextSecondary,
  outline = CardBorderGray,
  outlineVariant = Color(0xFFEAECF0)
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = PaircartColorScheme,
    typography = Typography,
    content = content
  )
}
