package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
      /** 播放错误，重试间隔（毫秒） */
      retryOnErrorInterval: Long = 5000,
      /** 追帧（毫秒） */
      chaseLatency: Long = 200,
    ): ComposePlayerRtsp {
      val rtspSourceFactory = RtspMediaSource.Factory()
        .setForceUseRtpTcp(forceUseRtpTcp)
        .setTimeoutMs(Long.MAX_VALUE)
      return RtspPlayerImpl(
        context = context.applicationContext,
        playerProvider = { ctx -> newLivePlayer(ctx, disableAudio = disableAudio) },
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
  override fun pause() {
    super.stop()
  }

  override fun seekTo(positionMs: Long) = Unit

  override fun release() {
    stopChaseLatencyJob()
    stopBufferingTimeoutJob()
    super.release()
  }

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    super.onIsPlayingChanged(isPlaying)
    if (isPlaying) {
      startChaseLatencyJob()
    } else {
      stopChaseLatencyJob()
    }
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    super.onPlaybackStateChanged(playbackState)
    if (playbackState == Player.STATE_BUFFERING) {
      startBufferingTimeoutJob()
    } else {
      stopBufferingTimeoutJob()
    }
  }

  private fun startBufferingTimeoutJob() {
    handler.removeCallbacks(_bufferingTimeoutJob)
    handler.postDelayed(_bufferingTimeoutJob, 5000)
  }

  private fun stopBufferingTimeoutJob() {
    handler.removeCallbacks(_bufferingTimeoutJob)
  }

  /** 缓冲超时任务 */
  private val _bufferingTimeoutJob = Runnable {
    if (media3Player?.playbackState == Player.STATE_BUFFERING) {
      stopPlayer()
      startPlayer()
    }
  }

  /** 开始追帧 */
  private fun startChaseLatencyJob() {
    if (chaseLatency > 0) {
      handler.removeCallbacks(_chaseLatencyJob)
      handler.post(_chaseLatencyJob)
    }
  }

  /** 停止追帧 */
  private fun stopChaseLatencyJob() {
    if (chaseLatency > 0) {
      handler.removeCallbacks(_chaseLatencyJob)
    }
  }

  /** 追帧任务 */
  private val _chaseLatencyJob = object : Runnable {
    override fun run() {
      if (chaseLatency <= 0) return
      val player = media3Player ?: return
      if (player.isPlaying) {
        val bufferedPosition = player.bufferedPosition
        val currentPosition = player.currentPosition
        if (bufferedPosition != C.TIME_UNSET && currentPosition != C.TIME_UNSET) {
          val drift = bufferedPosition - currentPosition
          val userSpeed = speedFlow.value
          if (drift > chaseLatency) {
            player.extSetSpeed(userSpeed * 1.2f)
          } else if (drift < (chaseLatency / 2)) {
            player.extSetSpeed(userSpeed)
          }
        }
        handler.postDelayed(this, chaseLatency / 2)
      }
    }
  }
}

@SuppressLint("UnsafeOptInUsageError")
private fun newLivePlayer(
  context: Context,
  disableAudio: Boolean,
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
  }

  return ExoPlayer.Builder(context, renderersFactory)
    .setLoadControl(loadController)
    .build()
    .also { player ->
      if (disableAudio) {
        player.extSetMute(true)
      }
    }
}