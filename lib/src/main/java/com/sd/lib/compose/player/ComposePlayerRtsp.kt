package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
        timeoutMs = timeoutMs,
        playerProvider = { ctx ->
          newLivePlayer(
            context = ctx,
            mediaSourceFactory = rtspSourceFactory,
            enableDecoderFallback = enableDecoderFallback,
            handleAudioFocus = handleAudioFocus,
          )
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
  private val timeoutMs: Long,
  private val playerProvider: (Context) -> ExoPlayer,
) : BaseComposePlayer(), ComposePlayerRtsp {
  private val _eventFlow = MutableSharedFlow<ComposePlayerRtspEvent>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  private var _isPlayingRequested = false
  private var _isWatchdogRunning = false

  private var _lastPosition = -1L
  private var _lastPositionChangeTime = 0L

  private var _lastRenderedFrameCount = -1
  private var _lastFrameRenderedTime = 0L

  /** 播放守护任务：负责卡死检查和追帧 */
  private val _playWatchdogJob = object : Runnable {
    override fun run() {
      if (!_isWatchdogRunning) return

      engine.exoPlayer?.also { player ->
        val now = SystemClock.elapsedRealtime()
        val currentPosition = player.currentPosition
        val isPlaying = player.isPlaying

        // 检查进度是否在前进（连接与缓冲阶段放宽至 timeoutMs 之后，避免抢先打断底层超时异常）
        if (stuckPositionInterval > 0) {
          val threshold = if (engine.playerStateFlow.value == ComposePlayerState.Playing || timeoutMs <= 0) {
            stuckPositionInterval
          } else {
            maxOf(stuckPositionInterval, timeoutMs + 2_000L)
          }
          if (_lastPosition != currentPosition) {
            _lastPosition = currentPosition
            _lastPositionChangeTime = now
          } else if (_lastPositionChangeTime > 0 && now - _lastPositionChangeTime > threshold) {
            _eventFlow.tryEmit(ComposePlayerRtspEvent.StuckPosition)
            restartPlay()
            return
          }
        }

        // 检查渲染帧数是否在增加，仅在实际播放且绑定有效渲染表面时检测
        val surfaceSize = player.surfaceSize
        val hasValidSurface = isPlaying && surfaceSize.width > 0 && surfaceSize.height > 0
        if (stuckRenderedFrameInterval > 0 && hasValidSurface) {
          player.videoDecoderCounters?.also { counters ->
            val currentRenderedFrameCount = counters.renderedOutputBufferCount
            if (_lastRenderedFrameCount != currentRenderedFrameCount) {
              _lastRenderedFrameCount = currentRenderedFrameCount
              _lastFrameRenderedTime = now
            } else if (_lastFrameRenderedTime > 0 && now - _lastFrameRenderedTime > stuckRenderedFrameInterval) {
              _eventFlow.tryEmit(ComposePlayerRtspEvent.StuckRenderedFrame)
              restartPlay()
              return
            }
          }
        } else {
          _lastRenderedFrameCount = -1
          _lastFrameRenderedTime = now
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

      if (_isWatchdogRunning) {
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

  override val eventFlow: Flow<ComposePlayerRtspEvent> = _eventFlow.asSharedFlow()

  override fun setDataSource(uri: String) {
    if (engine.setDataSource(uri)) {
      stopPlayWatchdogJob()
      if (_isPlayingRequested) {
        startStream()
      }
    }
  }

  override fun play() {
    _isPlayingRequested = true
    engine.stopRetry()
    startStream()
  }

  override fun stop() {
    _isPlayingRequested = false
    stopPlayWatchdogJob()
    engine.stopPlayer()
  }

  override fun release() {
    _isPlayingRequested = false
    stopPlayWatchdogJob()
    engine.release()
  }

  /** 启动底层拉流并在媒体绑定后开启守护监控 */
  private fun startStream() {
    engine.startPlayer()
    if (_isPlayingRequested && engine.isMediaBound) {
      startPlayWatchdogJob()
    }
  }

  private fun restartPlay() {
    stopPlayWatchdogJob()
    engine.stopPlayer()
    startStream()
  }

  /** 开始播放守护任务 */
  private fun startPlayWatchdogJob() {
    if (stuckPositionInterval <= 0
      && stuckRenderedFrameInterval <= 0
      && chaseLatency <= 0
    ) {
      return
    }

    val now = SystemClock.elapsedRealtime()
    _lastPositionChangeTime = now
    _lastFrameRenderedTime = now

    if (!_isWatchdogRunning) {
      _isWatchdogRunning = true
      _lastPosition = -1L
      _lastRenderedFrameCount = -1
      engine.handler.removeCallbacks(_playWatchdogJob)
      engine.handler.post(_playWatchdogJob)
    }
  }

  /** 停止播放守护任务并恢复默认播放倍速 */
  private fun stopPlayWatchdogJob() {
    if (_isWatchdogRunning) {
      _isWatchdogRunning = false
      engine.handler.removeCallbacks(_playWatchdogJob)
      engine.exoPlayer?.extSetSpeed(1.0f)
    }
  }

  private inner class EngineCallback : PlayerEngine.Callback {
    override fun createExoPlayer(context: Context): ExoPlayer {
      return playerProvider(context)
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
      if (_isPlayingRequested) {
        restartPlay()
      } else {
        engine.stopPlayer()
      }
    }

    override fun onPlayerError(error: ComposePlayerException) {
      stopPlayWatchdogJob()
      val canRetry = error is ComposePlayerException.Playback && _isPlayingRequested
      val scheduled = canRetry && engine.startRetry {
        if (_isPlayingRequested) {
          startStream()
        }
      }
      if (!scheduled) {
        _isPlayingRequested = false
      }
    }
  }
}

/** 创建低延迟直播 ExoPlayer 实例 */
@SuppressLint("UnsafeOptInUsageError")
private fun newLivePlayer(
  context: Context,
  mediaSourceFactory: MediaSource.Factory,
  enableDecoderFallback: Boolean,
  handleAudioFocus: Boolean,
): ExoPlayer {
  val loadController = DefaultLoadControl.Builder()
    .setBufferDurationsMs(32, 30000, 0, 0)
    .setPrioritizeTimeOverSizeThresholds(true)
    .setBackBuffer(0, false)
    .build()

  val renderersFactory = DefaultRenderersFactory(context)
    .setEnableDecoderFallback(enableDecoderFallback)
    .setAllowedVideoJoiningTimeMs(0L)

  return ExoPlayer.Builder(context, renderersFactory)
    .setMediaSourceFactory(mediaSourceFactory)
    .setLoadControl(loadController)
    .setAudioAttributes(newMediaAudioAttributes(), handleAudioFocus)
    .build()
}
