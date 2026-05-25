package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.concurrent.atomic.AtomicBoolean

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

  /** 回调对象 */
  fun setEventCallback(callback: EventCallback)

  abstract class EventCallback {
    /** 进度卡住 */
    open fun onStuckPosition() = Unit

    /** 渲染帧卡住 */
    open fun onStuckRenderedFrame() = Unit
  }

  companion object {
    @SuppressLint("UnsafeOptInUsageError")
    fun create(
      context: Context,
      /** 是否强制使用TCP */
      forceUseRtpTcp: Boolean = true,
      /** 是否开启解码回退 */
      enableDecoderFallback: Boolean = true,
      /** 进度卡住检测间隔（毫秒），大于0生效 */
      stuckPositionInterval: Long = 5_000,
      /** 渲染帧卡住检测间隔（毫秒），大于0生效 */
      stuckRenderedFrameInterval: Long = 5_000,
      /** 播放错误，重试间隔（毫秒） */
      retryOnErrorInterval: Long = 10_000,
      /** 追帧（毫秒），大于0生效 */
      chaseLatency: Long = 200,
    ): ComposePlayerRtsp {
      val rtspSourceFactory = RtspMediaSource.Factory()
        .setForceUseRtpTcp(forceUseRtpTcp)
      return RtspPlayerImpl(
        context = context.applicationContext,
        playerProvider = { ctx ->
          newLivePlayer(
            context = ctx,
            enableDecoderFallback = enableDecoderFallback,
          )
        },
        setMedia = { uri ->
          val mediaSource = rtspSourceFactory.createMediaSource(MediaItem.fromUri(uri))
          setMediaSource(mediaSource)
        },
        retryOnErrorInterval = retryOnErrorInterval,
        stuckPositionInterval = stuckPositionInterval,
        stuckRenderedFrameInterval = stuckRenderedFrameInterval,
        chaseLatency = chaseLatency,
      )
    }
  }
}

@OptIn(UnstableApi::class)
private class RtspPlayerImpl(
  context: Context,
  playerProvider: (Context) -> ExoPlayer,
  setMedia: ExoPlayer.(String) -> Unit,
  retryOnErrorInterval: Long,
  private val stuckPositionInterval: Long,
  private val stuckRenderedFrameInterval: Long,
  private val chaseLatency: Long,
) : PlayerImpl(
  context = context,
  playerProvider = playerProvider,
  setMedia = setMedia,
  retryOnErrorInterval = retryOnErrorInterval,
), ComposePlayerRtsp {
  private val _startPlayWatchdogJob = AtomicBoolean()

  private var _lastPosition = -1L
  private var _lastPositionChangeTime = 0L

  private var _lastRenderedFrameCount = -1
  private var _lastFrameRenderedTime = 0L

  private var _eventCallback: ComposePlayerRtsp.EventCallback? = null

  override fun setEventCallback(callback: ComposePlayerRtsp.EventCallback) {
    _eventCallback = callback
  }

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
      startPlayWatchdogJob()
    }
  }

  override fun onRequireStateChanged(state: ComposePlayerState?) {
    super.onRequireStateChanged(state)
    if (state != ComposePlayerState.Playing) {
      stopPlayWatchdogJob()
    }
  }

  override fun handleStateEnded() {
    restartPlay()
  }

  private fun restartPlay() {
    stopPlayWatchdogJob()
    stopPlayer()
    startPlayer()
  }

  /** 开始播放守护任务 */
  private fun startPlayWatchdogJob() {
    if (stuckPositionInterval <= 0
      && stuckRenderedFrameInterval <= 0
      && chaseLatency <= 0
    ) {
      // 未配置参数，不需要守护任务
      return
    }

    if (_startPlayWatchdogJob.compareAndSet(false, true)) {
      _lastPosition = -1L
      _lastPositionChangeTime = SystemClock.elapsedRealtime()

      _lastRenderedFrameCount = -1
      _lastFrameRenderedTime = SystemClock.elapsedRealtime()

      handler.removeCallbacks(_playWatchdogJob)
      handler.post(_playWatchdogJob)
    }
  }

  /** 停止播放守护任务 */
  private fun stopPlayWatchdogJob() {
    if (_startPlayWatchdogJob.compareAndSet(true, false)) {
      handler.removeCallbacks(_playWatchdogJob)
    }
  }

  /** 播放守护任务：负责卡死检查和追帧 */
  private val _playWatchdogJob = object : Runnable {
    override fun run() {
      if (!_startPlayWatchdogJob.get()) return

      exoPlayer?.also { player ->
        val now = SystemClock.elapsedRealtime()
        val currentPosition = player.currentPosition

        // 检查进度是否在前进
        if (stuckPositionInterval > 0) {
          if (_lastPosition != currentPosition) {
            _lastPosition = currentPosition
            _lastPositionChangeTime = now
          } else {
            if (_lastPositionChangeTime > 0 && now - _lastPositionChangeTime > stuckPositionInterval) {
              _eventCallback?.onStuckPosition()
              restartPlay()
              return
            }
          }
        }

        // 检查渲染帧数是否在增加
        if (stuckRenderedFrameInterval > 0) {
          player.videoDecoderCounters?.also { counters ->
            val currentRenderedFrameCount = counters.renderedOutputBufferCount
            if (_lastRenderedFrameCount != currentRenderedFrameCount) {
              _lastRenderedFrameCount = currentRenderedFrameCount
              _lastFrameRenderedTime = now
            } else {
              if (_lastFrameRenderedTime > 0 && now - _lastFrameRenderedTime > stuckRenderedFrameInterval) {
                _eventCallback?.onStuckRenderedFrame()
                restartPlay()
                return
              }
            }
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
      }

      if (_startPlayWatchdogJob.get()) {
        val interval = if (chaseLatency > 0) (chaseLatency / 2).coerceAtLeast(100) else 1000L
        handler.postDelayed(this, interval)
      }
    }
  }
}

@SuppressLint("UnsafeOptInUsageError")
private fun newLivePlayer(
  context: Context,
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
}