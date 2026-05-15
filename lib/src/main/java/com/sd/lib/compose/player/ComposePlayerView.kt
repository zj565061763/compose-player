package com.sd.lib.compose.player

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun ComposePlayerView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
  contentScale: ContentScale = ContentScale.Fit,
  surfaceType: ComposePlayerViewSurfaceType = ComposePlayerViewSurfaceType.TextureView,
  rebindKeyProvider: @Composable () -> Any = {},
) {
  require(player is PlayerImpl)

  val exoPlayer = player.exoPlayer
  val presentationState = rememberPresentationState(exoPlayer)

  key(rebindKeyProvider()) {
    PlayerSurface(
      modifier = modifier.resizeWithContentScale(
        contentScale = contentScale,
        sourceSizeDp = presentationState.videoSizeDp,
      ),
      player = exoPlayer,
      surfaceType = when (surfaceType) {
        ComposePlayerViewSurfaceType.SurfaceView -> SURFACE_TYPE_SURFACE_VIEW
        ComposePlayerViewSurfaceType.TextureView -> SURFACE_TYPE_TEXTURE_VIEW
      },
    )
  }
}

/**
 * onResume时，重新绑定[ComposePlayer]和[ComposePlayerView]，
 * 适用于同一个[ComposePlayer]关联多个页面[ComposePlayerView]的使用场景
 */
@Composable
fun composePlayerViewRebindKeyOnResume(key: Any? = Unit): Any {
  var resumeCount by remember(key) { mutableIntStateOf(0) }
  LifecycleResumeEffect(key) {
    resumeCount++
    onPauseOrDispose {}
  }
  return resumeCount
}

enum class ComposePlayerViewSurfaceType {
  SurfaceView,
  TextureView,
}