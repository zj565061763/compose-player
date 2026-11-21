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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/** 在 Compose 中记住并在退出组合时自动释放 [ComposePlayerRtsp] */
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

/** RTSP 直播播放器接口 */
sealed interface ComposePlayerRtsp : ComposePlayerCore {
  /** RTSP 直播异常事件流（如进度或渲染帧卡死触发重连） */
  val eventFlow: Flow<ComposePlayerRtspEvent>

  companion object {
    /**
     * 创建 [ComposePlayerRtsp] 实例
     *
     * @param context 上下文
     * @param forceUseRtpTcp 是否强制使用 TCP 传输 RTP
     * @param enableDecoderFallback 是否开启解码器回退
     * @param handleAudioFocus 是否自动管理音频焦点，多路监控场景默认关闭
     * @param stuckPositionInterval 进度卡住检测间隔（毫秒），大于 0 时生效
     * @param stuckRenderedFrameInterval 渲染帧卡住检测间隔（毫秒），大于 0 时生效
     * @param retryOnErrorInterval 播放错误自动重试间隔（毫秒），小于等于 0 时不重试
     * @param chaseLatency 追帧延迟阈值（毫秒），大于 0 时生效
     * @param timeoutMs RTSP 无数据包超时时间（毫秒），大于 0 时生效
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun create(
      context: Context,
      forceUseRtpTcp: Boolean = true,
      enableDecoderFallback: Boolean = true,
      handleAudioFocus: Boolean = false,
      stuckPositionInterval: Long = 5_000,
      stuckRenderedFrameInterval: Long = 5_000,
      retryOnErrorInterval: Long = 10_000,
      chaseLatency: Long = 200,
      timeoutMs: Long = 8_000,
    ): ComposePlayerRtsp {
      val appContext = context.applicationContext
      val rtspSourceFactory = RtspMediaSource.Factory()
        .setForceUseRtpTcp(forceUseRtpTcp)
        .also { factory ->
          if (timeoutMs > 0) {
            factory.setTimeoutMs(timeoutMs)
          }
        }

      return RtspPlayerImpl(
        context = appContext,
        retryOnErrorInterval = retryOnErrorInterval,
        stuckPositionInterval = stuckPositionInterval,
        stuckRenderedFrameInterval = stuckRenderedFrameInterval,
        chaseLatency = chaseLatency,
        playerProvider = { ctx ->
          newLivePlayer(
            context = ctx,
            enableDecoderFallback = enableDecoderFallback,
            handleAudioFocus = handleAudioFocus,
          )
        },
        setMedia = { uri ->
          val mediaSource = rtspSourceFactory.createMediaSource(MediaItem.fromUri(uri))
          setMediaSource(mediaSource)
        },
      )
    }
  }
}

/** RTSP 直播播放器事件 */
enum class ComposePlayerRtspEvent {
  /** 进度卡住 */
  StuckPosition,

  /** 渲染帧卡住 */
  StuckRenderedFrame,
}

@OptIn(UnstableApi::class)
internal class RtspPlayerImpl(
  context: Context,
  retryOnErrorInterval: Long,
  private val stuckPositionInterval: Long,
  private val stuckRenderedFrameInterval: Long,
  private val chaseLatency: Long,
  private val playerProvider: (Context) -> ExoPlayer,
  private val setMedia: ExoPlayer.(String) -> Unit,
) : ComposePlayerRtsp, PlayerEngineOwner {
  private val _eventFlow = MutableSharedFlow<ComposePlayerRtspEvent>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  private var _isPlayingRequested = false
  private val _startPlayWatchdogJob = AtomicBoolean()

  private var _lastPosition = -1L
  private var _lastPositionChangeTime = 0L

  private var _lastRenderedFrameCount = -1
  private var _lastFrameRenderedTime = 0L

  /** 播放守护任务：负责卡死检查和追帧 */
  private val _playWatchdogJob = object : Runnable {
    override fun run() {
      if (!_startPlayWatchdogJob.get()) return

      engine.exoPlayer?.also { player ->
        val now = SystemClock.elapsedRealtime()
        val currentPosition = player.currentPosition

        // 检查进度是否在前进
        if (stuckPositionInterval > 0) {
          if (_lastPosition != currentPosition) {
            _lastPosition = currentPosition
            _lastPositionChangeTime = now
          } else {
            if (_lastPositionChangeTime > 0 && now - _lastPositionChangeTime > stuckPositionInterval) {
              _eventFlow.tryEmit(ComposePlayerRtspEvent.StuckPosition)
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
                _eventFlow.tryEmit(ComposePlayerRtspEvent.StuckRenderedFrame)
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
            if (drift > chaseLatency) {
              player.extSetSpeed(1.2f)
            } else if (drift < (chaseLatency / 2)) {
              player.extSetSpeed(1.0f)
            }
          }
        }
      }

      if (_startPlayWatchdogJob.get()) {
        val interval = if (chaseLatency > 0) (chaseLatency / 2).coerceAtLeast(100) else 1000L
        engine.handler.postDelayed(this, interval)
      }
    }
  }

  override val engine: PlayerEngine = PlayerEngine(
    context = context,
    retryOnErrorInterval = retryOnErrorInterval,
    rebindMediaOnIdle = true,
    callback = EngineCallback(),
  )

  override val playerStateFlow: StateFlow<ComposePlayerState>
    get() = engine.playerStateFlow

  override val bufferStateFlow: StateFlow<ComposePlayerBufferState>
    get() = engine.bufferStateFlow

  override val exceptionFlow: StateFlow<ComposePlayerException?>
    get() = engine.exceptionFlow

  override val videoSizeFlow: StateFlow<Pair<Int, Int>?>
    get() = engine.videoSizeFlow

  override val isRenderedFirstFrameFlow: StateFlow<Boolean>
    get() = engine.isRenderedFirstFrameFlow

  override val isMutedFlow: StateFlow<Boolean>
    get() = engine.isMutedFlow

  override val eventFlow: Flow<ComposePlayerRtspEvent> = _eventFlow.asSharedFlow()

  override fun setDataSource(uri: String) {
    if (engine.setDataSource(uri)) {
      stopPlayWatchdogJob()
      if (_isPlayingRequested) {
        startPlayer()
      }
    }
  }

  override fun play() {
    _isPlayingRequested = true
    engine.stopRetry()
    startPlayer()
  }

  override fun stop() {
    _isPlayingRequested = false
    stopPlayWatchdogJob()
    engine.stopPlayer()
  }

  override fun getCurrentPosition(): Long {
    return engine.getCurrentPosition()
  }

  override fun setMute(mute: Boolean) {
    engine.setMute(mute)
  }

  override fun release() {
    _isPlayingRequested = false
    stopPlayWatchdogJob()
    engine.release()
  }

  private fun startPlayer() {
    engine.prepare()?.also { player ->
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0)
      }
      player.play()
    }
  }

  private fun restartPlay() {
    stopPlayWatchdogJob()
    engine.stopPlayer()
    startPlayer()
  }

  /** 开始播放守护任务 */
  private fun startPlayWatchdogJob() {
    if (stuckPositionInterval <= 0
      && stuckRenderedFrameInterval <= 0
      && chaseLatency <= 0
    ) {
      return
    }

    if (_startPlayWatchdogJob.compareAndSet(false, true)) {
      _lastPosition = -1L
      _lastPositionChangeTime = SystemClock.elapsedRealtime()

      _lastRenderedFrameCount = -1
      _lastFrameRenderedTime = SystemClock.elapsedRealtime()

      engine.handler.removeCallbacks(_playWatchdogJob)
      engine.handler.post(_playWatchdogJob)
    }
  }

  /** 停止播放守护任务并恢复默认播放倍速 */
  private fun stopPlayWatchdogJob() {
    if (_startPlayWatchdogJob.compareAndSet(true, false)) {
      engine.handler.removeCallbacks(_playWatchdogJob)
      engine.exoPlayer?.extSetSpeed(1.0f)
    }
  }

  private inner class EngineCallback : PlayerEngine.Callback {
    override fun createExoPlayer(context: Context): ExoPlayer {
      return playerProvider(context)
    }

    override fun bindMedia(player: ExoPlayer, uri: String) {
      setMedia(player, uri)
    }

    override fun onIsPlayingChanged(player: ExoPlayer?, isPlaying: Boolean) {
      if (isPlaying) {
        engine.setPlayerState(ComposePlayerState.Playing)
        startPlayWatchdogJob()
      } else if ((player?.playbackState ?: Player.STATE_IDLE) == Player.STATE_READY) {
        stopPlayWatchdogJob()
      }
    }

    override fun onPlayWhenReadyLost() {
      if (_isPlayingRequested) {
        stop()
      }
    }

    override fun onStateIdle() {
      stopPlayWatchdogJob()
    }

    override fun onStateReady(player: ExoPlayer) {
      if (_isPlayingRequested) {
        player.play()
      } else {
        engine.stopPlayer()
      }
    }

    override fun onStateEnded(player: ExoPlayer) {
      restartPlay()
    }

    override fun onPlayerError(error: PlaybackException) {
      val canRetry = !error.isAuthError() && _isPlayingRequested
      val scheduled = canRetry && engine.startRetry {
        if (_isPlayingRequested) {
          startPlayer()
        }
      }
      if (!scheduled) {
        _isPlayingRequested = false
        stopPlayWatchdogJob()
      }
    }
  }
}

/** 创建低延迟直播 ExoPlayer 实例 */
@SuppressLint("UnsafeOptInUsageError")
private fun newLivePlayer(
  context: Context,
  enableDecoderFallback: Boolean,
  handleAudioFocus: Boolean,
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
        context,
        extensionRendererMode,
        mediaCodecSelector,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        0L,
        out,
      )
    }
  }.setEnableDecoderFallback(enableDecoderFallback)

  val audioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

  return ExoPlayer.Builder(context, renderersFactory)
    .setLoadControl(loadController)
    .setAudioAttributes(audioAttributes, handleAudioFocus)
    .build()
}
