package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ComposePlayer {
  val playerStateFlow: StateFlow<ComposePlayerState>

  val bufferStateFlow: StateFlow<ComposePlayerBufferState>

  /** 回调对象 */
  fun setCallback(callback: Callback)

  /** 设置数据源 */
  fun setDataSource(uri: String)

  /** 开始播放 */
  fun play()

  /** 暂停播放 */
  fun pause()

  /** 停止播放 */
  fun stop()

  /** 移动进度到指定时间点 */
  fun seekTo(positionMs: Long)

  /** 当前播放进度时间点 */
  fun getCurrentPosition(): Long

  /** 释放 */
  fun release()

  abstract class Callback {
    /** 播放器状态变化 */
    open fun onPlayerStateChanged(state: ComposePlayerState) = Unit

    /** 缓冲状态变化 */
    open fun onPlayerBufferStateChanged(state: ComposePlayerBufferState) = Unit

    /** 播放器错误 */
    open fun onPlayerError(error: ComposePlayerException) = Unit
  }

  companion object {
    fun create(
      context: Context,
      /** 播放错误，重试间隔（毫秒） */
      retryOnErrorInterval: Long = 0,
    ): ComposePlayer {
      return PlayerImpl(
        context = context.applicationContext,
        playerProvider = { ctx -> ExoPlayer.Builder(ctx).build() },
        setMedia = { uri -> setMediaItem(MediaItem.fromUri(uri)) },
        retryOnErrorInterval = retryOnErrorInterval,
      )
    }
  }
}

enum class ComposePlayerState {
  /** 空闲 */
  Idle,

  /** 播放中 */
  Playing,

  /** 暂停 */
  Paused,

  /** 播放结束 */
  Ended,
}

enum class ComposePlayerBufferState {
  /** 空闲 */
  None,

  /** 缓冲中 */
  Buffering,

  /** 准备完毕 */
  Ready,
}

@OptIn(UnstableApi::class)
internal open class PlayerImpl(
  private val context: Context,
  private val playerProvider: (Context) -> ExoPlayer,
  private val setMedia: ExoPlayer.(String) -> Unit,
  private val retryOnErrorInterval: Long,
) : ComposePlayer, Player.Listener {
  private var _exoPlayer by mutableStateOf<ExoPlayer?>(null)

  private val _playerStateFlow: MutableStateFlow<ComposePlayerState> = MutableStateFlow(ComposePlayerState.Idle)
  private val _bufferStateFlow: MutableStateFlow<ComposePlayerBufferState> = MutableStateFlow(ComposePlayerBufferState.None)

  private var _requireState: ComposePlayerState? = null
  private var _dataSource = ""
  private var _seekToPositionMs: Long? = null
  private var _callback: ComposePlayer.Callback? = null

  protected val handler = Handler(Looper.getMainLooper())

  val media3Player: Player?
    get() = _exoPlayer

  override val playerStateFlow: StateFlow<ComposePlayerState> = _playerStateFlow.asStateFlow()
  override val bufferStateFlow: StateFlow<ComposePlayerBufferState> = _bufferStateFlow.asStateFlow()

  override fun setCallback(callback: ComposePlayer.Callback) {
    _callback = callback
  }

  override fun setDataSource(uri: String) {
    if (_dataSource != uri) {
      _dataSource = uri
      stopPlayer()
      updatePlayer()
    }
  }

  override fun play() {
    _requireState = ComposePlayerState.Playing
    stopRetry()
    updatePlayer()
  }

  override fun pause() {
    _requireState = ComposePlayerState.Paused
    stopRetry()
    updatePlayer()
  }

  override fun stop() {
    _requireState = ComposePlayerState.Idle
    stopRetry()
    updatePlayer()
  }

  override fun seekTo(positionMs: Long) {
    _exoPlayer?.seekTo(positionMs)
    _seekToPositionMs = if (_exoPlayer?.currentPosition == positionMs) null else positionMs
  }

  override fun getCurrentPosition(): Long {
    return _exoPlayer?.currentPosition ?: -1
  }

  @CallSuper
  override fun release() {
    _requireState = null
    _exoPlayer?.also {
      _exoPlayer = null
      it.removeListener(this@PlayerImpl)
      it.release()
    }
    stopRetry()
    _dataSource = ""
    _callback = null
    setBufferState(ComposePlayerBufferState.None)
    setPlayerState(ComposePlayerState.Idle)
  }

  private fun updatePlayer() {
    when (_requireState) {
      ComposePlayerState.Idle -> stopPlayer()
      ComposePlayerState.Playing -> startPlayer()
      ComposePlayerState.Paused -> pausePlayer()
      ComposePlayerState.Ended -> {}
      null -> {}
    }
  }

  private fun prepare() {
    val dataSource = _dataSource
    if (dataSource.isEmpty()) return

    val player = _exoPlayer ?: playerProvider(context).also { player ->
      _exoPlayer = player
      player.playWhenReady = false
      player.addListener(this@PlayerImpl)
    }

    if (player.playbackState == Player.STATE_IDLE) {
      runCatching {
        encodeUserInfoIfNeed(dataSource)
      }.onSuccess { uri ->
        setMedia(player, uri)
        player.prepare()
      }.onFailure { e ->
        _callback?.onPlayerError(ComposePlayerExceptionDataSource(cause = e))
      }
    }
  }

  /** 开始播放 */
  protected fun startPlayer() {
    prepare()
    _exoPlayer?.also { player ->
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0)
      }
      player.play()
    }
  }

  /** 停止播放 */
  protected fun stopPlayer() {
    _exoPlayer?.also { player ->
      if (player.playbackState != Player.STATE_IDLE) {
        player.stop()
      }
    }
  }

  /** 暂停播放 */
  private fun pausePlayer() {
    prepare()
    _exoPlayer?.also { player ->
      player.pause()
    }
  }

  @CallSuper
  override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (isPlaying) {
      setPlayerState(ComposePlayerState.Playing)
    } else {
      if (_requireState == ComposePlayerState.Paused
        || (_exoPlayer?.playbackState ?: Player.STATE_IDLE) == Player.STATE_READY
      ) {
        setPlayerState(ComposePlayerState.Paused)
      }
    }
  }

  @CallSuper
  override fun onPlaybackStateChanged(playbackState: Int) {
    when (playbackState) {
      Player.STATE_BUFFERING -> {
        setBufferState(ComposePlayerBufferState.Buffering)
      }
      Player.STATE_READY -> {
        setBufferState(ComposePlayerBufferState.Ready)
      }
      else -> {
        setBufferState(ComposePlayerBufferState.None)
      }
    }

    when (playbackState) {
      Player.STATE_IDLE -> {
        setPlayerState(ComposePlayerState.Idle)
      }
      Player.STATE_READY -> {
        stopRetry()
        _seekToPositionMs?.also { seekTo(it) }
        updatePlayer()
      }
      Player.STATE_ENDED -> {
        _requireState = ComposePlayerState.Ended
        setPlayerState(ComposePlayerState.Ended)
      }
      else -> {}
    }
  }

  override fun onPlayerError(error: PlaybackException) {
    if (startRetry()) {
      // 已经发起重试
    } else {
      _requireState = ComposePlayerState.Idle
    }
    _callback?.onPlayerError(ComposePlayerExceptionPlaybackException(error))
  }

  private fun setPlayerState(state: ComposePlayerState) {
    if (_playerStateFlow.value == state) return
    _playerStateFlow.value = state
    _callback?.onPlayerStateChanged(state)
  }

  private fun setBufferState(state: ComposePlayerBufferState) {
    if (_bufferStateFlow.value == state) return
    _bufferStateFlow.value = state
    _callback?.onPlayerBufferStateChanged(state)
  }

  private var _retryJob: Runnable? = null

  private fun startRetry(): Boolean {
    if (retryOnErrorInterval <= 0) return false
    return when (val requireState = _requireState) {
      ComposePlayerState.Playing,
      ComposePlayerState.Paused,
        -> {
        Runnable {
          when (requireState) {
            ComposePlayerState.Playing -> startPlayer()
            ComposePlayerState.Paused -> pausePlayer()
            else -> error("This should not happen")
          }
        }.also { job ->
          stopRetry()
          _retryJob = job
          handler.postDelayed(job, retryOnErrorInterval)
        }
        true
      }
      else -> false
    }
  }

  private fun stopRetry() {
    _retryJob?.also {
      _retryJob = null
      handler.removeCallbacks(it)
    }
  }
}

private fun encodeUserInfoIfNeed(uri: String): String {
  if (uri.isEmpty()) return uri

  @SuppressLint("UseKtx")
  val androidUri = Uri.parse(uri)

  // 获取原始的 encodedUserInfo（带百分比编码的），如果本身没带凭据则直接返回
  val rawEncodedUserInfo = androidUri.encodedUserInfo ?: return uri
  if (rawEncodedUserInfo.isEmpty()) return uri

  // 获取解码后的 userInfo 进行拆分，如果为空也返回
  val decodedUserInfo = androidUri.userInfo ?: return uri
  if (decodedUserInfo.isEmpty()) return uri

  // 拆分用户名和密码
  val parts = decodedUserInfo.split(":", limit = 2)
  val username = parts[0]
  val password = if (parts.size > 1) parts[1] else null

  // 分别对用户名和密码进行标准的百分比编码
  val encodedUsername = Uri.encode(username)
  val encodedPassword = password?.let { Uri.encode(it) }

  // 重新组合编码后的 UserInfo
  val newUserInfo = if (encodedPassword != null) {
    "$encodedUsername:$encodedPassword"
  } else {
    encodedUsername
  }

  // 如果编码后和原始的一样，说明不需要处理
  if (newUserInfo == rawEncodedUserInfo) return uri

  // 获取原始的 encodedAuthority (例如 "user:pass@192.168.1.1:554")
  val authority = androidUri.encodedAuthority ?: return uri

  val index = authority.lastIndexOf('@')
  val newAuthority = if (index >= 0) {
    // 替换 '@' 之前的部分，保留 '@' 及其之后的部分（host:port）
    newUserInfo + authority.substring(index)
  } else {
    // 理论上 rawEncodedUserInfo 不为空时 index 必定 >= 0，这里做个保险
    "$newUserInfo@$authority"
  }

  return androidUri.buildUpon()
    .encodedAuthority(newAuthority)
    .build()
    .toString()
}