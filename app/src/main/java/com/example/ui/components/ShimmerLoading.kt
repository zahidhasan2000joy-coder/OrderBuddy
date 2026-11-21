package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.OrderGreen

@Composable
fun rememberShimmerBrush(): Brush {
  val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
  val highlightColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
  val shimmerColors = listOf(baseColor, highlightColor, baseColor)

  val transition = rememberInfiniteTransition(label = "facebook_feed_shimmer")
  val translateAnim by transition.animateFloat(
    initialValue = -300f,
    targetValue = 1300f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1150, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "shimmer_offset"
  )

  return Brush.linearGradient(
    colors = shimmerColors,
    start = Offset(x = translateAnim - 280f, y = translateAnim * 0.25f - 120f),
    end = Offset(x = translateAnim, y = translateAnim * 0.25f)
  )
}

@Composable
fun ShimmerBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(8.dp),
  brush: Brush = rememberShimmerBrush()
) {
  Box(
    modifier = modifier
      .clip(shape)
      .background(brush)
  )
}

@Composable
fun FacebookSlimShimmerBar(
  modifier: Modifier = Modifier,
  accentColor: Color = OrderGreen
) {
  val transition = rememberInfiniteTransition(label = "slim_shimmer_bar")
  val slide by transition.animateFloat(
    initialValue = -250f,
    targetValue = 1100f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1050, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "slim_shimmer_x"
  )

  val brush = Brush.linearGradient(
    colors = listOf(
      accentColor.copy(alpha = 0.16f),
      accentColor.copy(alpha = 0.88f),
      accentColor.copy(alpha = 0.16f)
    ),
    start = Offset(slide - 240f, 0f),
    end = Offset(slide, 0f)
  )

  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(3.dp)
      .background(brush)
  )
}

@Composable
fun FacebookFeedOrderSkeletonCard(
  modifier: Modifier = Modifier,
  brush: Brush = rememberShimmerBrush()
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Feed Post Header (Avatar + Title/Time + Status Pill)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          ShimmerBox(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            brush = brush
          )
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShimmerBox(
              modifier = Modifier
                .width(128.dp)
                .height(13.dp),
              shape = RoundedCornerShape(6.dp),
              brush = brush
            )
            ShimmerBox(
              modifier = Modifier
                .width(82.dp)
                .height(10.dp),
              shape = RoundedCornerShape(5.dp),
              brush = brush
            )
          }
        }
        ShimmerBox(
          modifier = Modifier
            .width(68.dp)
            .height(24.dp),
          shape = RoundedCornerShape(12.dp),
          brush = brush
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))

      // Feed Content Lines (Items)
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(2) { idx ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              ShimmerBox(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(8.dp),
                brush = brush
              )
              Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ShimmerBox(
                  modifier = Modifier
                    .fillMaxWidth(if (idx == 0) 0.72f else 0.56f)
                    .height(12.dp),
                  shape = RoundedCornerShape(6.dp),
                  brush = brush
                )
                ShimmerBox(
                  modifier = Modifier
                    .fillMaxWidth(0.38f)
                    .height(9.dp),
                  shape = RoundedCornerShape(5.dp),
                  brush = brush
                )
              }
            }
            ShimmerBox(
              modifier = Modifier
                .width(52.dp)
                .height(22.dp),
              shape = RoundedCornerShape(8.dp),
              brush = brush
            )
          }
        }
      }

      // Feed Footer Action Pills
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        ShimmerBox(
          modifier = Modifier
            .weight(1f)
            .height(36.dp),
          shape = RoundedCornerShape(10.dp),
          brush = brush
        )
        ShimmerBox(
          modifier = Modifier
            .weight(1f)
            .height(36.dp),
          shape = RoundedCornerShape(10.dp),
          brush = brush
        )
      }
    }
  }
}

@Composable
fun FacebookFeedSkeletonList(
  count: Int = 3,
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    repeat(count) {
      FacebookFeedOrderSkeletonCard(brush = brush)
    }
  }
}

@Composable
fun FacebookAppEntrySkeleton(isOnline: Boolean) {
  val brush = rememberShimmerBrush()
  Surface(
    color = MaterialTheme.colorScheme.background,
    modifier = Modifier
      .fillMaxSize()
      .testTag("app_entry_loading_overlay")
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top App Bar Skeleton matching Paircart Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Image(
          painter = painterResource(id = R.drawable.app_wordmark_logo),
          contentDescription = "Paircart Logo",
          contentScale = ContentScale.Fit,
          modifier = Modifier.height(22.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ShimmerBox(modifier = Modifier.size(34.dp), shape = CircleShape, brush = brush)
          ShimmerBox(modifier = Modifier.size(34.dp), shape = CircleShape, brush = brush)
        }
      }

      // Partner Card Skeleton
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            ShimmerBox(modifier = Modifier.size(38.dp), shape = CircleShape, brush = brush)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              ShimmerBox(modifier = Modifier.width(120.dp).height(12.dp), brush = brush)
              ShimmerBox(modifier = Modifier.width(86.dp).height(10.dp), brush = brush)
            }
          }
          ShimmerBox(modifier = Modifier.width(72.dp).height(28.dp), shape = RoundedCornerShape(10.dp), brush = brush)
        }
      }

      // Feed Post Skeleton Cards
      repeat(3) {
        FacebookFeedOrderSkeletonCard(brush = brush)
      }
    }
  }
}

@Composable
fun FacebookProfileInfoSkeleton(
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .testTag("profile_loading_indicator"),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    repeat(3) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        ShimmerBox(modifier = Modifier.size(20.dp), shape = CircleShape, brush = brush)
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          ShimmerBox(modifier = Modifier.width(72.dp).height(10.dp), brush = brush)
          ShimmerBox(modifier = Modifier.fillMaxWidth(0.68f).height(13.dp), brush = brush)
        }
      }
    }
  }
}

@Composable
fun FacebookNotificationsSkeletonList(
  count: Int = 5,
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    repeat(count) {
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top
        ) {
          ShimmerBox(modifier = Modifier.size(40.dp), shape = CircleShape, brush = brush)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp)
          ) {
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.65f).height(13.dp), brush = brush)
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.92f).height(11.dp), brush = brush)
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.45f).height(9.dp), brush = brush)
          }
        }
      }
    }
  }
}

@Composable
fun FacebookPartnerConnectionSkeletonCard(
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    modifier = modifier
      .fillMaxWidth()
      .testTag("partner_connection_skeleton")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f)
      ) {
        ShimmerBox(modifier = Modifier.size(42.dp), shape = CircleShape, brush = brush)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          ShimmerBox(modifier = Modifier.width(115.dp).height(12.dp), brush = brush)
          ShimmerBox(modifier = Modifier.width(150.dp).height(10.dp), brush = brush)
        }
      }
      ShimmerBox(
        modifier = Modifier.width(74.dp).height(28.dp),
        shape = RoundedCornerShape(10.dp),
        brush = brush
      )
    }
  }
}

@Composable
fun FacebookNoticeSkeletonCard(
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    modifier = modifier
      .fillMaxWidth()
      .height(152.dp)
      .testTag("notice_skeleton_card")
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        ShimmerBox(modifier = Modifier.width(72.dp).height(22.dp), shape = RoundedCornerShape(10.dp), brush = brush)
        ShimmerBox(modifier = Modifier.width(38.dp).height(20.dp), shape = RoundedCornerShape(10.dp), brush = brush)
      }
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.62f).height(16.dp), brush = brush)
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.95f).height(12.dp), brush = brush)
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.75f).height(12.dp), brush = brush)
      }
    }
  }
}

@Composable
fun FacebookProfileSummarySkeleton(
  modifier: Modifier = Modifier
) {
  val brush = rememberShimmerBrush()
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          ShimmerBox(modifier = Modifier.size(36.dp), shape = CircleShape, brush = brush)
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShimmerBox(modifier = Modifier.width(124.dp).height(13.dp), brush = brush)
            ShimmerBox(modifier = Modifier.width(86.dp).height(10.dp), brush = brush)
          }
        }
        ShimmerBox(modifier = Modifier.width(64.dp).height(26.dp), shape = RoundedCornerShape(10.dp), brush = brush)
      }
      ShimmerBox(modifier = Modifier.fillMaxWidth().height(10.dp), shape = RoundedCornerShape(5.dp), brush = brush)
      ShimmerBox(modifier = Modifier.fillMaxWidth(0.72f).height(10.dp), shape = RoundedCornerShape(5.dp), brush = brush)
    }
  }
}
