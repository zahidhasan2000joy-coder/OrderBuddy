package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.OrderGreen
import com.example.util.ProfileImageHelper
import java.util.Locale

@Composable
fun rememberEnhanced4kImageBitmap(photoData: String): ImageBitmap? {
  val immediateBitmap = remember(photoData) {
    val cached = ProfileImageHelper.getCachedEnhancedBitmap(photoData)
    (cached ?: ProfileImageHelper.decodeDataUriToBitmap(photoData))?.asImageBitmap()
  }
  var enhancedBitmap by remember(photoData) { mutableStateOf(immediateBitmap) }

  LaunchedEffect(photoData) {
    if (photoData.isNotBlank()) {
      val cached = ProfileImageHelper.getCachedEnhancedBitmap(photoData)
      if (cached != null) {
        enhancedBitmap = cached.asImageBitmap()
      } else {
        val upgraded = ProfileImageHelper.decodeAndEnhanceFor4kViewer(photoData)
        if (upgraded != null) {
          enhancedBitmap = upgraded.asImageBitmap()
        }
      }
    } else {
      enhancedBitmap = null
    }
  }

  return enhancedBitmap ?: immediateBitmap
}

@Composable
fun FullScreen4kPhotoDialog(
  photoData: String,
  title: String,
  subtitle: String = "",
  onDismiss: () -> Unit
) {
  val bitmap4k = rememberEnhanced4kImageBitmap(photoData)
  var scale by remember { mutableFloatStateOf(1f) }
  var offsetX by remember { mutableFloatStateOf(0f) }
  var offsetY by remember { mutableFloatStateOf(0f) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF07090C))
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("fullscreen_4k_photo_dialog")
    ) {
      if (bitmap4k != null) {
        Image(
          bitmap = bitmap4k,
          contentDescription = "$title Profile Picture",
          contentScale = ContentScale.Fit,
          filterQuality = FilterQuality.High,
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
              detectTapGestures(
                onDoubleTap = {
                  if (scale > 1.2f) {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                  } else {
                    scale = 2.5f
                  }
                }
              )
            }
            .pointerInput(Unit) {
              detectTransformGestures { _, pan, zoom, _ ->
                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                scale = nextScale
                if (nextScale > 1.02f) {
                  val maxOffsetX = (size.width * (nextScale - 1f)) / 2f
                  val maxOffsetY = (size.height * (nextScale - 1f)) / 2f
                  offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                  offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                } else {
                  offsetX = 0f
                  offsetY = 0f
                }
              }
            }
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
              translationX = offsetX
              translationY = offsetY
            }
        )
      } else {
        Column(
          modifier = Modifier.align(Alignment.Center),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "No Profile Picture",
            tint = OrderGreen,
            modifier = Modifier.size(72.dp)
          )
          Text(
            text = "No profile picture available",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }

      // Top clean header bar
      Surface(
        color = Color.Black.copy(alpha = 0.62f),
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = title.ifBlank { "Profile Photo" },
              color = Color.White,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
              Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall
              )
            }
          }

          Spacer(modifier = Modifier.width(12.dp))
          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(48.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.14f))
              .testTag("fullscreen_4k_photo_close_button")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Photo Viewer",
              tint = Color.White
            )
          }
        }
      }

      // Bottom zoom reset pill (only visible when zoomed in)
      if (bitmap4k != null && scale > 1.02f) {
        Surface(
          shape = RoundedCornerShape(999.dp),
          color = Color.Black.copy(alpha = 0.65f),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 20.dp)
            .clickable {
              scale = 1f
              offsetX = 0f
              offsetY = 0f
            }
        ) {
          Text(
            text = String.format(Locale.US, "Zoom %.1fx • Tap to reset", scale),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
          )
        }
      }
    }
  }
}
