package com.sd.lib.compose.player

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState

/**
 * 播放器视频渲染组件
 *
 * @param modifier 修饰符
 * @param player 播放器实例（支持 [ComposePlayer] 与 [ComposePlayerRtsp]）
 * @param contentScale 视频画面缩放模式
 * @param surfaceType 底层渲染表面类型
 * @param rebindKeyProvider 重新绑定渲染表面的键值提供器，跨页面共用同一 [player] 时可传入 [composePlayerViewRebindKeyOnResume]
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun ComposePlayerView(
  modifier: Modifier = Modifier,
  player: ComposePlayerCore,
  contentScale: ContentScale = ContentScale.Fit,
  surfaceType: ComposePlayerViewSurfaceType = ComposePlayerViewSurfaceType.TextureView,
  rebindKeyProvider: @Composable () -> Any = {},
) {
  Box(
    modifier = modifier.clipToBounds(),
    contentAlignment = Alignment.Center,
  ) {
    if (LocalInspectionMode.current) return@Box

    val exoPlayer = (player as? BaseComposePlayer)?.exoPlayer
    val isRenderedFirstFrame by player.isRenderedFirstFrameFlow.collectAsState()
    val videoSize by player.videoSizeFlow.collectAsState()
    val presentationState = rememberPresentationState(exoPlayer)
    val sourceSizeDp = if (videoSize != null) presentationState.videoSizeDp else null

    key(rebindKeyProvider()) {
      PlayerSurface(
        modifier = Modifier
          .fillMaxSize()
          .resizeWithContentScale(
            contentScale = contentScale,
            sourceSizeDp = sourceSizeDp,
          )
          .alpha(if (isRenderedFirstFrame) 1f else 0f),
        player = exoPlayer,
        surfaceType = when (surfaceType) {
          ComposePlayerViewSurfaceType.SurfaceView -> SURFACE_TYPE_SURFACE_VIEW
          ComposePlayerViewSurfaceType.TextureView -> SURFACE_TYPE_TEXTURE_VIEW
        },
      )
    }
  }
}

/** 在 `onResume` 时重新绑定 [ComposePlayerCore] 与 [ComposePlayerView]，适用于同一播放器关联多个页面的场景 */
@Composable
fun composePlayerViewRebindKeyOnResume(key: Any? = Unit): Any {
  var resumeCount by remember(key) { mutableIntStateOf(0) }
  var hasPaused by remember(key) { mutableStateOf(false) }
  LifecycleResumeEffect(key) {
    if (hasPaused) {
      hasPaused = false
      resumeCount++
    }
    onPauseOrDispose {
      hasPaused = true
    }
  }
  return resumeCount
}

/** 渲染表面类型 */
enum class ComposePlayerViewSurfaceType {
  /** SurfaceView */
  SurfaceView,

  /** TextureView */
  TextureView,
}
