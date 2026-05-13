package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener

@Composable
fun rememberComposePlayerRtsp(
  factory: (Context) -> ComposePlayerRtsp = { ComposePlayerRtsp.create(it) },
): ComposePlayerRtsp {
  val context = LocalContext.current
  return remember { factory(context) }.also { player ->
    DisposableEffect(player) {
      onDispose { player.release() }
    }
  }
}

interface ComposePlayerRtsp : ComposePlayer {
  companion object {
    @SuppressLint("UnsafeOptInUsageError")
    fun create(
      context: Context,
      /** 是否强制使用TCP */
      forceUseRtpTcp: Boolean = true,
      /** 是否禁用音频 */
      disableAudio: Boolean = true,
      /** 是否开启解码回退 */
      enableDecoderFallback: Boolean = true,
      /** 播放错误，重试间隔（毫秒） */
      retryOnErrorInterval: Long = 10_000,
      /** 追帧（毫秒） */
      chaseLatency: Long = 200,
    ): ComposePlayerRtsp {
      val rtspSourceFactory = RtspMediaSource.Factory()
        .setForceUseRtpTcp(forceUseRtpTcp)
      return RtspPlayerImpl(
        context = context.applicationContext,
        playerProvider = { ctx ->
          newLivePlayer(
            context = ctx,
            disableAudio = disableAudio,
            enableDecoderFallback = enableDecoderFallback,
          )
        },
        setMedia = { uri ->
          val mediaSource = rtspSourceFactory.createMediaSource(MediaItem.fromUri(uri))
          setMediaSource(mediaSource)
        },
        retryOnErrorInterval = retryOnErrorInterval,
        chaseLatency = chaseLatency,
      )
    }
  }
}

private class RtspPlayerImpl(
  context: Context,
  playerProvider: (Context) -> ExoPlayer,
  setMedia: ExoPlayer.(String) -> Unit,
  retryOnErrorInterval: Long,
  private val chaseLatency: Long,
) : PlayerImpl(
  context = context,
  playerProvider = playerProvider,
  setMedia = setMedia,
  retryOnErrorInterval = retryOnErrorInterval,
), ComposePlayerRtsp {
  private var _lastPosition = -1L
  private var _lastPositionChangeTime = 0L

  override fun pause() {
    super.stop()
  }

  override fun seekTo(positionMs: Long) = Unit
  override fun setLooping(looping: Boolean) = Unit

  override fun release() {
    stopPlayWatchdogJob()
    super.release()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    super.onIsPlayingChanged(isPlaying)
    if (isPlaying) {
      _lastPosition = -1L
      _lastPositionChangeTime = SystemClock.elapsedRealtime()
      startPlayWatchdogJob()
    } else {
      stopPlayWatchdogJob()
    }
  }

  override fun handleStateEnded() {
    stopPlayer()
    startPlayer()
  }

  /** 开始播放守护任务 */
  private fun startPlayWatchdogJob() {
    handler.removeCallbacks(_playWatchdogJob)
    handler.post(_playWatchdogJob)
  }

  /** 停止播放守护任务 */
  private fun stopPlayWatchdogJob() {
    handler.removeCallbacks(_playWatchdogJob)
  }

  /** 播放守护任务：负责进度卡死检查和追帧 */
  private val _playWatchdogJob = object : Runnable {
    override fun run() {
      val player = media3Player ?: return
      if (!player.isPlaying) return

      val currentPosition = player.currentPosition
      val now = SystemClock.elapsedRealtime()

      // 检查进度是否在前进
      if (currentPosition != _lastPosition) {
        _lastPosition = currentPosition
        _lastPositionChangeTime = now
      } else {
        if (_lastPositionChangeTime > 0 && now - _lastPositionChangeTime > 5_000) {
          _lastPositionChangeTime = now
          stopPlayer()
          startPlayer()
          return
        }
      }

      // 追帧逻辑
      if (chaseLatency > 0) {
        val bufferedPosition = player.bufferedPosition
        if (bufferedPosition != C.TIME_UNSET && currentPosition != C.TIME_UNSET) {
          val drift = bufferedPosition - currentPosition
          val userSpeed = speedFlow.value
          if (drift > chaseLatency) {
            player.extSetSpeed(userSpeed * 1.2f)
          } else if (drift < (chaseLatency / 2)) {
            player.extSetSpeed(userSpeed)
          }
        }
      }

      val interval = if (chaseLatency > 0) (chaseLatency / 2).coerceAtLeast(100) else 1000L
      handler.postDelayed(this, interval)
    }
  }
}

@SuppressLint("UnsafeOptInUsageError")
private fun newLivePlayer(
  context: Context,
  disableAudio: Boolean,
  enableDecoderFallback: Boolean,
): ExoPlayer {
  val loadController = DefaultLoadControl.Builder()
    .setBufferDurationsMs(32, 30000, 0, 0)
    .setPrioritizeTimeOverSizeThresholds(true)
    .setBackBuffer(0, false)
    .build()

  val renderersFactory = object : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
      context: Context,
      extensionRendererMode: Int,
      mediaCodecSelector: MediaCodecSelector,
      enableDecoderFallback: Boolean,
      eventHandler: Handler,
      eventListener: VideoRendererEventListener,
      allowedVideoJoiningTimeMs: Long,
      out: ArrayList<Renderer>,
    ) {
      super.buildVideoRenderers(
        context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
        eventHandler, eventListener, 0L, out
      )
    }
  }.setEnableDecoderFallback(enableDecoderFallback)

  return ExoPlayer.Builder(context, renderersFactory)
    .setLoadControl(loadController)
    .build()
    .also { player ->
      if (disableAudio) {
        player.extSetMute(true)
      }
    }
}