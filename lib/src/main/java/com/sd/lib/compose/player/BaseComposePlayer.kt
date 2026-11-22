package com.sd.lib.compose.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** 播放器内部公共基类，统一管理底层 ExoPlayer 生命周期、共用状态流、进度采样与错误重试 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@OptIn(UnstableApi::class)
internal abstract class BaseComposePlayer(
  private val context: Context,
  private val retryOnErrorInterval: Long,
  private val rebindMediaOnIdle: Boolean = false,
) : ComposePlayerCore {
  var exoPlayer by mutableStateOf<ExoPlayer?>(null)
    private set
  var videoSizeDp by mutableStateOf<Size?>(null)
    private set

  protected val mainScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
  private var _pendingSeekToDefaultPosition = false
  private var _retryJob: Job? = null

  protected var pendingStartPositionMs: Long = C.TIME_UNSET

  protected val hasDataSource: Boolean
    get() = _dataSource != null

  protected val isMediaBound: Boolean
    get() = _isMediaBound

  override val playerStateFlow: StateFlow<ComposePlayerState> = _playerStateFlow.asStateFlow()
  override val bufferStateFlow: StateFlow<ComposePlayerBufferState> = _bufferStateFlow.asStateFlow()
  override val exceptionFlow: StateFlow<ComposePlayerException?> = _exceptionFlow.asStateFlow()
  override val videoSizeFlow: StateFlow<Pair<Int, Int>?> = _videoSizeFlow.asStateFlow()
  override val isRenderedFirstFrameFlow: StateFlow<Boolean> = _isRenderedFirstFrameFlow.asStateFlow()
  override val isMutedFlow: StateFlow<Boolean> = _isMutedFlow.asStateFlow()

  private val _playerListener = object : Player.Listener {
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
      if (!playWhenReady
        && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
      ) {
        onPlayWhenReadyLost()
      }
    }

    override fun onEvents(rawPlayer: Player, events: Player.Events) {
      val player = exoPlayer ?: return
      if (player !== rawPlayer) return

      if (events.contains(Player.EVENT_PLAYER_ERROR)) {
        player.playerError?.also { error ->
          if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            _pendingSeekToDefaultPosition = true
          }
          syncStates(player, events)
          val exception = error.toComposePlayerException()
          handlePlayerError(exception)
          notifyPositionChanged()
          setException(exception)
          return
        }
      }

      if (events.contains(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) {
        if (onPlaybackSuppressionChanged(player, player.playbackSuppressionReason)) {
          return
        }
      }

      if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
        when (player.playbackState) {
          Player.STATE_READY -> {
            stopRetry()
            setException(null)
            if (!onStateReady(player)) return
          }
          Player.STATE_ENDED -> {
            if (!onStateEnded(player)) return
          }
          else -> {}
        }
      }

      syncStates(player, events)
    }
  }

  override fun getCurrentPosition(): Long {
    if (!_isMediaBound) {
      return if (pendingStartPositionMs != C.TIME_UNSET) pendingStartPositionMs else 0L
    }
    return (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
  }

  override fun positionFlow(interval: Duration): Flow<Long> {
    val safeInterval = if (interval.isPositive()) interval else 200.milliseconds
    val statePositionFlow = playerStateFlow.transformLatest { state ->
      emit(getCurrentPosition())
      if (state == ComposePlayerState.Playing) {
        while (true) {
          delay(safeInterval)
          emit(getCurrentPosition())
        }
      }
    }
    val changedPositionFlow = _positionChangedFlow.map { getCurrentPosition() }
    return merge(changedPositionFlow, statePositionFlow)
      .flowOn(Dispatchers.Main.immediate)
      .distinctUntilChanged()
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
      handlePlayerError(exception)
      setException(exception)
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
        handlePlayerError(exception)
        setException(exception)
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
        val startPositionMs = pendingStartPositionMs
        if (startPositionMs != C.TIME_UNSET) {
          player.setMediaItem(mediaItem, startPositionMs)
        } else {
          player.setMediaItem(mediaItem)
        }
        pendingStartPositionMs = C.TIME_UNSET
        _pendingSeekToDefaultPosition = false
        _isMediaBound = true
        _isRenderedFirstFrameFlow.value = false
      }.onFailure { e ->
        val exception = ComposePlayerException.DataSource(cause = e)
        handlePlayerError(exception)
        setException(exception)
        return null
      }
    } else if (_pendingSeekToDefaultPosition) {
      _pendingSeekToDefaultPosition = false
      player.seekToDefaultPosition()
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
    _pendingSeekToDefaultPosition = false
    pendingStartPositionMs = C.TIME_UNSET
    exoPlayer?.also { player ->
      if (player.playbackState != Player.STATE_IDLE) {
        player.stop()
      }
      player.clearMediaItems()
    }
    syncStates(null, null)
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
    mainScope.coroutineContext.cancelChildren()
  }

  /** 按当前播放器快照重新计算并同步输出状态 */
  protected fun syncPlayerState() {
    syncStates(exoPlayer, null)
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
    stopRetry()
    _retryJob = mainScope.launch {
      delay(retryOnErrorInterval.milliseconds)
      _retryJob = null
      action()
    }
    return true
  }

  protected fun stopRetry() {
    _retryJob?.also { job ->
      _retryJob = null
      job.cancel()
    }
  }

  /** 同步缓冲态、视频尺寸、首帧标记与播放态 */
  private fun syncStates(
    player: ExoPlayer?,
    events: Player.Events?,
  ) {
    val boundPlayer = if (_isMediaBound) player else null
    val playbackState = boundPlayer?.playbackState ?: Player.STATE_IDLE

    _bufferStateFlow.value = when (playbackState) {
      Player.STATE_BUFFERING -> ComposePlayerBufferState.Buffering
      Player.STATE_READY -> ComposePlayerBufferState.Ready
      else -> ComposePlayerBufferState.None
    }

    if (playbackState == Player.STATE_IDLE) {
      _videoSizeFlow.value = null
      videoSizeDp = null
      _isRenderedFirstFrameFlow.value = false
    } else if (boundPlayer != null) {
      val videoSize = boundPlayer.videoSize
      if (videoSize.width > 0 && videoSize.height > 0) {
        _videoSizeFlow.value = Pair(videoSize.width, videoSize.height)
        videoSizeDp = videoSize.toComposeSizeDp()
      } else {
        _videoSizeFlow.value = null
        videoSizeDp = null
      }
      if (events?.contains(Player.EVENT_RENDERED_FIRST_FRAME) == true) {
        _isRenderedFirstFrameFlow.value = true
      }
    }

    val previousState = _playerStateFlow.value
    val newState = computePlayerState(boundPlayer, previousState)
    _playerStateFlow.value = newState
    onStateSynced(boundPlayer, previousState, newState)

    if (previousState != newState
      || events?.containsAny(
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_IS_PLAYING_CHANGED,
        Player.EVENT_POSITION_DISCONTINUITY,
      ) == true
    ) {
      notifyPositionChanged()
    }
  }

  protected abstract fun createExoPlayer(context: Context): ExoPlayer
  protected abstract fun computePlayerState(
    player: ExoPlayer?,
    currentState: ComposePlayerState,
  ): ComposePlayerState
  protected abstract fun onStateReady(player: ExoPlayer): Boolean
  protected abstract fun onStateEnded(player: ExoPlayer): Boolean
  protected abstract fun handlePlayerError(error: ComposePlayerException)

  protected open fun validateEncodedUri(encodedUri: String) {}
  protected open fun onPlayerCreated(player: ExoPlayer) {}
  protected open fun onPlayWhenReadyLost() {}
  protected open fun onPlaybackSuppressionChanged(player: ExoPlayer, reason: Int): Boolean {
    return false
  }
  protected open fun onStateSynced(
    player: ExoPlayer?,
    previousState: ComposePlayerState,
    newState: ComposePlayerState,
  ) {}
}

/** 将 [VideoSize] 按像素宽高比折算为 Compose 尺寸 */
private fun VideoSize.toComposeSizeDp(): Size? {
  if (width <= 0 || height <= 0) return null
  var size = Size(width.toFloat(), height.toFloat())
  val par = pixelWidthHeightRatio
  if (par > 0f && par.isFinite()) {
    if (par < 1f) {
      size = size.copy(width = size.width * par)
    } else if (par > 1f) {
      size = size.copy(height = size.height / par)
    }
  }
  return size
}
