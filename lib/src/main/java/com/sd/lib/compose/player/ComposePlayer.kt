package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** 在 Compose 中记住并在退出组合时自动释放 [ComposePlayer] */
@Composable
fun rememberComposePlayer(
  factory: (Context) -> ComposePlayer = { ComposePlayer.create(it) },
): ComposePlayer {
  val context = LocalContext.current
  return remember { factory(context) }.also { player ->
    DisposableEffect(player) {
      onDispose { player.release() }
    }
  }
}

/** 常规点播播放器接口 */
sealed interface ComposePlayer : ComposePlayerCore {
  /** 总时长（毫秒），null 表示未知，非 null 时一定大于 0 */
  val durationFlow: StateFlow<Long?>

  /** 播放倍速 */
  val speedFlow: StateFlow<Float>

  /** 是否循环播放 */
  val isLoopingFlow: StateFlow<Boolean>

  /** 暂停播放 */
  fun pause()

  /** 移动进度到指定时间点（毫秒） */
  fun seekTo(positionMs: Long)

  /** 总时长（毫秒），null 表示未知，非 null 时一定大于 0 */
  fun getDuration(): Long?

  /** 设置播放倍速 */
  fun setSpeed(speed: Float)

  /** 设置是否循环播放 */
  fun setLooping(looping: Boolean)

  companion object {
    /**
     * 创建 [ComposePlayer] 实例
     *
     * @param context 上下文
     * @param retryOnErrorInterval 播放错误自动重试间隔（毫秒），小于等于 0 时不重试
     * @param enableDecoderFallback 是否开启解码器回退
     * @param handleAudioFocus 是否自动管理音频焦点
     * @param handleAudioBecomingNoisy 拔出耳机等音频输出切换时是否自动暂停
     * @param httpHeaders 自定义 HTTP 请求头
     * @param connectTimeoutMs HTTP 连接超时时间（毫秒）
     * @param readTimeoutMs HTTP 读取超时时间（毫秒）
     * @param allowCrossProtocolRedirects 是否允许 HTTP 与 HTTPS 跨协议重定向
     * @param cache 磁盘缓存配置
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun create(
      context: Context,
      retryOnErrorInterval: Long = 10_000,
      enableDecoderFallback: Boolean = true,
      handleAudioFocus: Boolean = true,
      handleAudioBecomingNoisy: Boolean = true,
      httpHeaders: Map<String, String> = emptyMap(),
      connectTimeoutMs: Int = 8_000,
      readTimeoutMs: Int = 8_000,
      allowCrossProtocolRedirects: Boolean = true,
      cache: ComposePlayerCache? = null,
    ): ComposePlayer {
      val appContext = context.applicationContext
      return VodPlayerImpl(
        context = appContext,
        retryOnErrorInterval = retryOnErrorInterval,
        playerProvider = { ctx ->
          val renderersFactory = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(enableDecoderFallback)

          val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
            .setAllowCrossProtocolRedirects(allowCrossProtocolRedirects)
            .also { factory ->
              if (httpHeaders.isNotEmpty()) {
                factory.setDefaultRequestProperties(httpHeaders)
              }
            }

          val baseDataSourceFactory = if (cache != null) {
            CacheDataSource.Factory()
              .setCache(cache.cache)
              .setUpstreamDataSourceFactory(httpDataSourceFactory)
              .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
          } else {
            httpDataSourceFactory
          }

          val dataSourceFactory = DefaultDataSource.Factory(ctx, baseDataSourceFactory)
          ExoPlayer.Builder(ctx, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(newMediaAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
            .build()
        },
      )
    }
  }
}

/** 挂起等待获取总时长[ComposePlayer.durationFlow] */
suspend fun ComposePlayer.awaitDuration(): Long {
  return durationFlow.filterNotNull().first()
}

/** 按偏移量（毫秒）移动播放进度 */
fun ComposePlayer.seekDelta(ms: Long) {
  seekTo(getCurrentPosition() + ms)
}

@OptIn(UnstableApi::class)
internal class VodPlayerImpl(
  context: Context,
  retryOnErrorInterval: Long,
  private val playerProvider: (Context) -> ExoPlayer,
) : BaseComposePlayer(), ComposePlayer {
  private val _durationFlow = MutableStateFlow<Long?>(null)
  private val _speedFlow = MutableStateFlow(1.0f)
  private val _isLoopingFlow = MutableStateFlow(false)

  private var _requireState: ComposePlayerState? = null
  private var _seekToPositionMs: Long? = null

  override val engine: PlayerEngine = PlayerEngine(
    context = context,
    retryOnErrorInterval = retryOnErrorInterval,
    rebindMediaOnIdle = false,
    callback = EngineCallback(),
  )

  override val durationFlow: StateFlow<Long?> = _durationFlow.asStateFlow()
  override val speedFlow: StateFlow<Float> = _speedFlow.asStateFlow()
  override val isLoopingFlow: StateFlow<Boolean> = _isLoopingFlow.asStateFlow()

  override fun setDataSource(uri: String) {
    if (engine.setDataSource(uri)) {
      if (_requireState == ComposePlayerState.Ended) {
        _requireState = ComposePlayerState.Idle
      }
      syncPlayer()
    }
  }

  override fun play() {
    _requireState = ComposePlayerState.Playing
    engine.stopRetry()
    syncPlayer()
  }

  override fun pause() {
    _requireState = ComposePlayerState.Paused
    engine.stopRetry()
    syncPlayer()
  }

  override fun stop() {
    _requireState = ComposePlayerState.Idle
    engine.stopRetry()
    syncPlayer()
  }

  override fun seekTo(positionMs: Long) {
    if (_requireState == ComposePlayerState.Ended) {
      _requireState = ComposePlayerState.Paused
      if (engine.playerStateFlow.value == ComposePlayerState.Ended) {
        engine.setPlayerState(ComposePlayerState.Paused)
      }
    }

    val player = engine.exoPlayer
    if (player == null || !engine.isMediaBound) {
      _seekToPositionMs = positionMs.coerceAtLeast(0L)
      engine.notifyPositionChanged()
      return
    }

    val duration = getDuration()
    val targetPosition = if (duration != null) {
      positionMs.coerceIn(0L, duration)
    } else {
      positionMs.coerceAtLeast(0L)
    }
    _seekToPositionMs = null
    player.seekTo(targetPosition)
    engine.notifyPositionChanged()
  }

  override fun getDuration(): Long? {
    val duration = engine.exoPlayer?.duration ?: return null
    return if (duration == C.TIME_UNSET) null else duration.takeIf { it > 0 }
  }

  override fun setSpeed(speed: Float) {
    require(speed > 0f && speed.isFinite()) { "speed must be positive and finite: $speed" }
    if (_speedFlow.value != speed) {
      _speedFlow.value = speed
      engine.exoPlayer?.extSetSpeed(speed)
    }
  }

  override fun setLooping(looping: Boolean) {
    if (_isLoopingFlow.value != looping) {
      _isLoopingFlow.value = looping
      engine.exoPlayer?.extSetLooping(looping)
    }
  }

  override fun release() {
    _requireState = null
    _seekToPositionMs = null
    _durationFlow.value = null
    engine.release()
  }

  private fun syncPlayer() {
    when (_requireState) {
      ComposePlayerState.Idle -> engine.stopPlayer()
      ComposePlayerState.Playing -> engine.startPlayer()
      ComposePlayerState.Paused -> pausePlayer()
      ComposePlayerState.Ended -> {}
      null -> {}
    }
  }

  private fun pausePlayer() {
    engine.prepare()?.also { player ->
      player.pause()
      if (player.playbackState == Player.STATE_READY
        || engine.playerStateFlow.value == ComposePlayerState.Playing
      ) {
        engine.setPlayerState(ComposePlayerState.Paused)
      }
    }
  }

  private fun updateDurationFlow() {
    _durationFlow.value = getDuration()
  }

  private inner class EngineCallback : PlayerEngine.Callback {
    override fun createExoPlayer(context: Context): ExoPlayer {
      return playerProvider(context)
    }

    override fun getPendingPositionMs(): Long? {
      return _seekToPositionMs
    }

    override fun consumeStartPositionMs(): Long {
      val positionMs = _seekToPositionMs ?: return C.TIME_UNSET
      _seekToPositionMs = null
      return positionMs.coerceAtLeast(0L)
    }

    override fun onPlayerCreated(player: ExoPlayer) {
      if (_speedFlow.value != 1.0f) {
        player.extSetSpeed(_speedFlow.value)
      }
      if (_isLoopingFlow.value) {
        player.extSetLooping(true)
      }
    }

    override fun onStopped() {
      _seekToPositionMs = null
    }

    override fun onIsPlayingChanged(player: ExoPlayer?, isPlaying: Boolean) {
      if (isPlaying) {
        engine.setPlayerState(ComposePlayerState.Playing)
      } else if ((player?.playbackState ?: Player.STATE_IDLE) == Player.STATE_READY) {
        engine.setPlayerState(ComposePlayerState.Paused)
      }
    }

    override fun onPlayWhenReadyLost() {
      if (_requireState == ComposePlayerState.Playing) {
        _requireState = ComposePlayerState.Paused
        if (engine.playerStateFlow.value == ComposePlayerState.Playing) {
          engine.setPlayerState(ComposePlayerState.Paused)
        }
      }
    }

    override fun onStateIdle() {
      _durationFlow.value = null
    }

    override fun onStateReady(player: ExoPlayer) {
      updateDurationFlow()
      syncPlayer()
    }

    override fun onStateEnded(player: ExoPlayer) {
      player.pause()
      _requireState = ComposePlayerState.Ended
      engine.setPlayerState(ComposePlayerState.Ended)
    }

    override fun onTimelineOrMediaChanged(player: ExoPlayer) {
      updateDurationFlow()
    }

    override fun onPlayerError(error: ComposePlayerException) {
      val requireState = _requireState
      val canRetry = error is ComposePlayerException.Playback
        && (requireState == ComposePlayerState.Playing || requireState == ComposePlayerState.Paused)
      val scheduled = canRetry && engine.startRetry {
        when (requireState) {
          ComposePlayerState.Playing -> engine.startPlayer()
          ComposePlayerState.Paused -> pausePlayer()
          else -> {}
        }
      }
      if (!scheduled) {
        _requireState = ComposePlayerState.Idle
      }
    }
  }
}
