package com.sd.lib.compose.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState

@OptIn(UnstableApi::class)
@Composable
fun ComposePlayerView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
  contentScale: ContentScale = ContentScale.Fit,
  surfaceType: ComposePlayerViewSurfaceType = ComposePlayerViewSurfaceType.SurfaceView,
) {
  require(player is PlayerImpl)

  val media3Player = player.media3Player
  val presentationState = rememberPresentationState(media3Player)

  PlayerSurface(
    modifier = modifier.resizeWithContentScale(
      contentScale = contentScale,
      sourceSizeDp = presentationState.videoSizeDp,
    ),
    player = media3Player,
    surfaceType = when (surfaceType) {
      ComposePlayerViewSurfaceType.SurfaceView -> SURFACE_TYPE_SURFACE_VIEW
      ComposePlayerViewSurfaceType.TextureView -> SURFACE_TYPE_TEXTURE_VIEW
    },
  )
}

enum class ComposePlayerViewSurfaceType {
  SurfaceView,
  TextureView,
}