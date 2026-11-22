package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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

  /** 预加载数据源（不自动开始播放） */
  @MainThread
  fun prepare()

  /** 暂停播放 */
  @MainThread
  fun pause()

  /** 移动进度到指定时间点（毫秒） */
  @MainThread
  fun seekTo(positionMs: Long)

  /** 总时长（毫秒），null 表示未知，非 null 时一定大于 0 */
  @MainThread
  fun getDuration(): Long?

  /** 设置播放倍速 */
  @MainThread
  fun setSpeed(speed: Float)

  /** 设置是否循环播放 */
  @MainThread
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
      ) { ctx ->
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
          DataSource.Factory {
            CacheDataSource.Factory()
              .setCache(cache.cache)
              .setUpstreamDataSourceFactory(httpDataSourceFactory)
              .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
              .createDataSource()
          }
        } else {
          httpDataSourceFactory
        }

        val dataSourceFactory = DefaultDataSource.Factory(ctx, baseDataSourceFactory)
        ExoPlayer.Builder(ctx, renderersFactory)
          .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory))
          .setAudioAttributes(newMediaAudioAttributes(), handleAudioFocus)
          .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
          .build()
      }
    }
  }
}

/** 设置 Raw 资源数据源 */
@MainThread
fun ComposePlayer.setDataSource(@RawRes resId: Int) {
  val uri = Uri.Builder()
    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
    .path(resId.toString())
    .build()
  setDataSource(uri.toString())
}

/** 挂起等待获取总时长[ComposePlayer.durationFlow] */
suspend fun ComposePlayer.awaitDuration(): Long {
  return durationFlow.filterNotNull().first()
}

/** 按偏移量（毫秒）移动播放进度 */
@MainThread
fun ComposePlayer.seekDelta(ms: Long) {
  seekTo(getCurrentPosition() + ms)
}

/** 点播内部控制意图 */
private enum class VodPlayIntent {
  Idle,
  Playing,
  Paused,
}

@OptIn(UnstableApi::class)
internal class VodPlayerImpl(
  context: Context,
  retryOnErrorInterval: Long,
  private val playerProvider: (Context) -> ExoPlayer,
) : BaseComposePlayer(
  context = context,
  retryOnErrorInterval = retryOnErrorInterval,
  rebindMediaOnIdle = false,
), ComposePlayer {
  private val _durationFlow = MutableStateFlow<Long?>(null)
  private val _speedFlow = MutableStateFlow(1.0f)
  private val _isLoopingFlow = MutableStateFlow(false)

  private var _playIntent = VodPlayIntent.Idle

  override val durationFlow: StateFlow<Long?> = _durationFlow.asStateFlow()
  override val speedFlow: StateFlow<Float> = _speedFlow.asStateFlow()
  override val isLoopingFlow: StateFlow<Boolean> = _isLoopingFlow.asStateFlow()

  override fun setDataSource(uri: String) {
    if (updateDataSource(uri)) {
      when (_playIntent) {
        VodPlayIntent.Playing -> startPlayer()
        VodPlayIntent.Paused -> _playIntent = VodPlayIntent.Idle
        VodPlayIntent.Idle -> {}
      }
    }
  }

  override fun prepare() {
    if (!hasDataSource || (isMediaBound && exoPlayer?.playbackState != Player.STATE_IDLE)) {
      return
    }
    stopRetry()
    _playIntent = VodPlayIntent.Paused
    preparePlayer()?.also { player ->
      player.pause()
      syncPlayerState()
    }
  }

  override fun play() {
    _playIntent = VodPlayIntent.Playing
    stopRetry()
    startPlayer()
  }

  override fun pause() {
    if (_playIntent != VodPlayIntent.Playing) return
    stopRetry()
    val player = exoPlayer
    if (player == null || !isMediaBound) {
      _playIntent = VodPlayIntent.Idle
      return
    }
    _playIntent = VodPlayIntent.Paused
    player.pause()
    syncPlayerState()
  }

  override fun stop() {
    _playIntent = VodPlayIntent.Idle
    stopPlayer()
  }

  override fun seekTo(positionMs: Long) {
    val player = exoPlayer
    if (player == null || !isMediaBound) {
      pendingStartPositionMs = positionMs.coerceAtLeast(0L)
      notifyPositionChanged()
      return
    }

    val duration = getDuration()
    val targetPosition = if (duration != null) {
      positionMs.coerceIn(0L, duration)
    } else {
      positionMs.coerceAtLeast(0L)
    }

    if (playerStateFlow.value == ComposePlayerState.Ended) {
      if (duration != null && targetPosition >= duration) {
        return
      }
      _playIntent = VodPlayIntent.Paused
    }

    pendingStartPositionMs = C.TIME_UNSET
    player.seekTo(targetPosition)
    syncPlayerState()
    notifyPositionChanged()
  }

  override fun getDuration(): Long? {
    if (!isMediaBound) return null
    val player = exoPlayer ?: return null
    if (player.playbackState == Player.STATE_IDLE) return null
    val duration = player.duration
    return if (duration == C.TIME_UNSET) null else duration.takeIf { it > 0 }
  }

  override fun setSpeed(speed: Float) {
    require(speed > 0f && speed.isFinite()) { "speed must be positive and finite: $speed" }
    if (_speedFlow.value != speed) {
      _speedFlow.value = speed
      exoPlayer?.extSetSpeed(speed)
    }
  }

  override fun setLooping(looping: Boolean) {
    if (_isLoopingFlow.value != looping) {
      _isLoopingFlow.value = looping
      exoPlayer?.extSetLooping(looping)
    }
  }

  override fun release() {
    _playIntent = VodPlayIntent.Idle
    releasePlayer()
  }

  override fun createExoPlayer(context: Context): ExoPlayer {
    return playerProvider(context)
  }

  override fun onPlayerCreated(player: ExoPlayer) {
    if (_speedFlow.value != 1.0f) {
      player.extSetSpeed(_speedFlow.value)
    }
    if (_isLoopingFlow.value) {
      player.extSetLooping(true)
    }
  }

  override fun computePlayerState(
    player: ExoPlayer?,
    currentState: ComposePlayerState,
  ): ComposePlayerState {
    if (player == null) return ComposePlayerState.Idle
    return when (player.playbackState) {
      Player.STATE_IDLE -> ComposePlayerState.Idle
      Player.STATE_ENDED -> ComposePlayerState.Ended
      Player.STATE_READY -> {
        if (_playIntent == VodPlayIntent.Idle) {
          ComposePlayerState.Idle
        } else if (player.isPlaying) {
          ComposePlayerState.Playing
        } else {
          ComposePlayerState.Paused
        }
      }
      Player.STATE_BUFFERING -> {
        if (currentState == ComposePlayerState.Idle || _playIntent == VodPlayIntent.Idle) {
          ComposePlayerState.Idle
        } else if (_playIntent == VodPlayIntent.Playing
          && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
          ComposePlayerState.Playing
        } else {
          ComposePlayerState.Paused
        }
      }
      else -> ComposePlayerState.Idle
    }
  }

  override fun onPlayWhenReadyLost() {
    if (_playIntent == VodPlayIntent.Playing) {
      _playIntent = VodPlayIntent.Paused
    }
  }

  override fun onStateEnded(player: ExoPlayer): Boolean {
    if (player.playWhenReady) {
      player.pause()
    }
    _playIntent = VodPlayIntent.Idle
    return true
  }

  override fun onStateSynced(
    player: ExoPlayer?,
    previousState: ComposePlayerState,
    newState: ComposePlayerState,
  ) {
    _durationFlow.value = getDuration()
  }

  override fun handlePlayerError(error: ComposePlayerException) {
    val intent = _playIntent
    val canRetry = error is ComposePlayerException.Playback
      && (intent == VodPlayIntent.Playing || intent == VodPlayIntent.Paused)
    val scheduled = canRetry && startRetry {
      when (_playIntent) {
        VodPlayIntent.Playing -> startPlayer()
        VodPlayIntent.Paused -> {
          preparePlayer()?.also { player ->
            player.pause()
            syncPlayerState()
          }
        }
        VodPlayIntent.Idle -> {}
      }
    }
    if (!scheduled) {
      _playIntent = VodPlayIntent.Idle
    }
  }
}
