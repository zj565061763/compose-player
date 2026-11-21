package com.sd.lib.compose.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 播放器内部公共基类，统一管理底层 ExoPlayer 生命周期、共用状态流、进度采样与错误重试 */
@OptIn(UnstableApi::class)
internal abstract class BaseComposePlayer(
  private val context: Context,
  private val retryOnErrorInterval: Long,
  private val rebindMediaOnIdle: Boolean = false,
) : ComposePlayerCore {
  var exoPlayer by mutableStateOf<ExoPlayer?>(null)
    private set

  protected val mainHandler: Handler = Handler(Looper.getMainLooper())

  private val _playerStateFlow = MutableStateFlow(ComposePlayerState.Idle)
  private val _bufferStateFlow = MutableStateFlow(ComposePlayerBufferState.None)
  private val _exceptionFlow = MutableStateFlow<ComposePlayerException?>(null)

  private val _videoSizeFlow = MutableStateFlow<Pair<Int, Int>?>(null)
  private val _isRenderedFirstFrameFlow = MutableStateFlow(false)
  private val _isMutedFlow = MutableStateFlow(false)

  private val _positionChangedFlow = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  private var _dataSource: String? = null
  private var _isMediaBound = false
  private var _retryJob: Runnable? = null

  protected val isMediaBound: Boolean
    get() = _isMediaBound

  override val playerStateFlow: StateFlow<ComposePlayerState> = _playerStateFlow.asStateFlow()
  override val bufferStateFlow: StateFlow<ComposePlayerBufferState> = _bufferStateFlow.asStateFlow()
  override val exceptionFlow: StateFlow<ComposePlayerException?> = _exceptionFlow.asStateFlow()
  override val videoSizeFlow: StateFlow<Pair<Int, Int>?> = _videoSizeFlow.asStateFlow()
  override val isRenderedFirstFrameFlow: StateFlow<Boolean> = _isRenderedFirstFrameFlow.asStateFlow()
  override val isMutedFlow: StateFlow<Boolean> = _isMutedFlow.asStateFlow()

  private val _playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      handleIsPlayingChanged(exoPlayer, isPlaying)
      notifyPositionChanged()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      if (!playWhenReady
        && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
      ) {
        onPlayWhenReadyLost()
      }
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
      exoPlayer?.also { handlePlaybackSuppressionReasonChanged(it, playbackSuppressionReason) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      _bufferStateFlow.value = when (playbackState) {
        Player.STATE_BUFFERING -> ComposePlayerBufferState.Buffering
        Player.STATE_READY -> ComposePlayerBufferState.Ready
        else -> ComposePlayerBufferState.None
      }

      when (playbackState) {
        Player.STATE_IDLE -> {
          resetIdleStates()
        }
        Player.STATE_READY -> {
          stopRetry()
          setException(null)
          exoPlayer?.also { onStateReady(it) }
        }
        Player.STATE_ENDED -> {
          exoPlayer?.also { onStateEnded(it) }
        }
        else -> {}
      }
      notifyPositionChanged()
    }

    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      notifyPositionChanged()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
      exoPlayer?.also { onTimelineOrMediaChanged(it) }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      exoPlayer?.also { onTimelineOrMediaChanged(it) }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
      _videoSizeFlow.value = if (videoSize.width > 0 && videoSize.height > 0) {
        Pair(videoSize.width, videoSize.height)
      } else {
        null
      }
    }

    override fun onRenderedFirstFrame() {
      _isRenderedFirstFrameFlow.value = true
    }

    override fun onPlayerError(error: PlaybackException) {
      if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        exoPlayer?.seekToDefaultPosition()
      }
      val exception = error.toComposePlayerException()
      setException(exception)
      handlePlayerError(exception)
    }
  }

  override fun getCurrentPosition(): Long {
    if (!_isMediaBound) {
      return getPendingPositionMs() ?: 0L
    }
    return (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
  }

  override fun setMute(mute: Boolean) {
    if (_isMutedFlow.value != mute) {
      _isMutedFlow.value = mute
      exoPlayer?.extSetMute(mute)
    }
  }

  /** 更新数据源，数据源发生变化时停止当前播放并返回 true */
  protected fun updateDataSource(uri: String): Boolean {
    val safeUri = uri.trim()
    if (_dataSource == safeUri) return false
    _dataSource = safeUri
    _isMediaBound = false
    stopPlayer()
    return true
  }

  /** 确保底层 ExoPlayer 已创建并完成数据源准备 */
  protected fun preparePlayer(): ExoPlayer? {
    val dataSource = _dataSource ?: return null
    if (dataSource.isBlank()) {
      val exception = ComposePlayerException.DataSourceBlank()
      setException(exception)
      handlePlayerError(exception)
      return null
    }

    val existingPlayer = exoPlayer
    if (existingPlayer != null && existingPlayer.playbackState != Player.STATE_IDLE) {
      return existingPlayer
    }

    val needBindMedia = existingPlayer == null || !_isMediaBound || rebindMediaOnIdle
    val encodedUri = if (needBindMedia) {
      runCatching {
        encodeUserInfoIfNeed(dataSource).also { validateEncodedUri(it) }
      }.getOrElse { e ->
        val exception = ComposePlayerException.DataSource(cause = e)
        setException(exception)
        handlePlayerError(exception)
        return null
      }
    } else {
      null
    }

    val player = existingPlayer ?: createExoPlayer(context).also { created ->
      exoPlayer = created
      _isMediaBound = false
      created.playWhenReady = false
      if (_isMutedFlow.value) created.extSetMute(true)
      onPlayerCreated(created)
      created.addListener(_playerListener)
    }

    if (encodedUri != null) {
      runCatching {
        val mediaItem = MediaItem.fromUri(encodedUri)
        val startPositionMs = consumeStartPositionMs()
        if (startPositionMs != C.TIME_UNSET) {
          player.setMediaItem(mediaItem, startPositionMs)
        } else {
          player.setMediaItem(mediaItem)
        }
        _isMediaBound = true
      }.onFailure { e ->
        val exception = ComposePlayerException.DataSource(cause = e)
        setException(exception)
        handlePlayerError(exception)
        return null
      }
    }
    player.prepare()
    return player
  }

  /** 准备数据源并开始播放，若已处于片尾或播放结束状态则先回到起点 */
  protected fun startPlayer() {
    preparePlayer()?.also { player ->
      val duration = player.duration
      if (player.playbackState == Player.STATE_ENDED
        || (duration != C.TIME_UNSET && duration > 0 && player.currentPosition >= duration)
      ) {
        player.seekTo(0)
      }
      player.play()
    }
  }

  /** 停止底层播放并清空当前媒体项、异常与重试任务 */
  protected fun stopPlayer() {
    stopRetry()
    setException(null)
    _isMediaBound = false
    exoPlayer?.also { player ->
      if (player.playbackState != Player.STATE_IDLE) {
        player.stop()
      }
      player.clearMediaItems()
    }
    resetIdleStates()
    onStopped()
    notifyPositionChanged()
  }

  /** 释放底层 ExoPlayer 实例与监听资源 */
  protected fun releasePlayer() {
    stopPlayer()
    exoPlayer?.also { player ->
      exoPlayer = null
      player.removeListener(_playerListener)
      player.clearVideoSurface()
      player.release()
    }
    _dataSource = null
  }

  protected fun setPlayerState(state: ComposePlayerState) {
    _playerStateFlow.value = state
  }

  protected fun setException(error: ComposePlayerException?) {
    _exceptionFlow.value = error
  }

  protected fun notifyPositionChanged() {
    _positionChangedFlow.tryEmit(Unit)
  }

  /** 调度延迟错误重试，配置未启用时返回 false */
  protected fun startRetry(action: () -> Unit): Boolean {
    if (retryOnErrorInterval <= 0) return false
    Runnable { action() }.also { job ->
      stopRetry()
      _retryJob = job
      mainHandler.postDelayed(job, retryOnErrorInterval)
    }
    return true
  }

  protected fun stopRetry() {
    _retryJob?.also { job ->
      _retryJob = null
      mainHandler.removeCallbacks(job)
    }
  }

  /** 构建按需采样的播放进度冷流 */
  internal fun observePosition(interval: Duration): Flow<Long> {
    val safeInterval = if (interval.isPositive()) interval else 200.milliseconds
    return channelFlow {
      launch {
        _positionChangedFlow.collect {
          send(getCurrentPosition())
        }
      }

      launch {
        playerStateFlow.collectLatest { state ->
          send(getCurrentPosition())
          if (state == ComposePlayerState.Playing) {
            while (true) {
              delay(safeInterval)
              send(getCurrentPosition())
            }
          }
        }
      }
    }.flowOn(Dispatchers.Main.immediate).distinctUntilChanged()
  }

  /** 重置为空闲状态并清空当前媒体的尺寸、首帧与缓冲状态 */
  private fun resetIdleStates() {
    _bufferStateFlow.value = ComposePlayerBufferState.None
    setPlayerState(ComposePlayerState.Idle)
    _videoSizeFlow.value = null
    _isRenderedFirstFrameFlow.value = false
    onStateIdle()
  }

  protected abstract fun createExoPlayer(context: Context): ExoPlayer
  protected open fun validateEncodedUri(encodedUri: String) {}
  protected open fun getPendingPositionMs(): Long? {
    return null
  }
  protected open fun consumeStartPositionMs(): Long {
    return C.TIME_UNSET
  }
  protected open fun onPlayerCreated(player: ExoPlayer) {}
  protected open fun onStopped() {}
  protected abstract fun handleIsPlayingChanged(player: ExoPlayer?, isPlaying: Boolean)
  protected open fun onPlayWhenReadyLost() {}
  protected open fun handlePlaybackSuppressionReasonChanged(player: ExoPlayer, reason: Int) {}
  protected open fun onStateIdle() {}
  protected open fun onStateReady(player: ExoPlayer) {}
  protected abstract fun onStateEnded(player: ExoPlayer)
  protected open fun onTimelineOrMediaChanged(player: ExoPlayer) {}
  protected abstract fun handlePlayerError(error: ComposePlayerException)
}
