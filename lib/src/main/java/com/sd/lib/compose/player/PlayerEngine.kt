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

private const val HEX_DIGITS = "0123456789ABCDEF"

/** 内部引擎持有者标记接口 */
internal interface PlayerEngineOwner {
  val engine: PlayerEngine
}

/** 播放器内部公共基类，统一向 [PlayerEngine] 转发共用状态与控制操作 */
internal abstract class BaseComposePlayer : ComposePlayerCore, PlayerEngineOwner {
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

  override fun getCurrentPosition(): Long {
    return engine.getCurrentPosition()
  }

  override fun setMute(mute: Boolean) {
    engine.setMute(mute)
  }
}

/** 内部共享的 ExoPlayer 宿主引擎，由点播与直播播放器组合复用 */
@OptIn(UnstableApi::class)
internal class PlayerEngine(
  private val context: Context,
  private val retryOnErrorInterval: Long,
  private val rebindMediaOnIdle: Boolean = false,
  private val callback: Callback,
) : Player.Listener {
  var exoPlayer by mutableStateOf<ExoPlayer?>(null)
    private set

  val handler: Handler = Handler(Looper.getMainLooper())

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

  val isMediaBound: Boolean
    get() = _isMediaBound

  val playerStateFlow: StateFlow<ComposePlayerState> = _playerStateFlow.asStateFlow()
  val bufferStateFlow: StateFlow<ComposePlayerBufferState> = _bufferStateFlow.asStateFlow()
  val exceptionFlow: StateFlow<ComposePlayerException?> = _exceptionFlow.asStateFlow()
  val videoSizeFlow: StateFlow<Pair<Int, Int>?> = _videoSizeFlow.asStateFlow()
  val isRenderedFirstFrameFlow: StateFlow<Boolean> = _isRenderedFirstFrameFlow.asStateFlow()
  val isMutedFlow: StateFlow<Boolean> = _isMutedFlow.asStateFlow()

  /** 更新数据源，数据源发生变化时停止当前播放并返回 true */
  fun setDataSource(uri: String): Boolean {
    val safeUri = uri.trim()
    if (_dataSource == safeUri) return false
    _dataSource = safeUri
    _isMediaBound = false
    stopPlayer()
    return true
  }

  /** 确保底层 ExoPlayer 已创建并完成数据源准备 */
  fun prepare(): ExoPlayer? {
    val dataSource = _dataSource ?: return null
    if (dataSource.isBlank()) {
      setException(ComposePlayerException.DataSourceBlank())
      return null
    }

    val player = exoPlayer ?: callback.createExoPlayer(context).also { created ->
      exoPlayer = created
      _isMediaBound = false
      created.playWhenReady = false
      if (_isMutedFlow.value) created.extSetMute(true)
      callback.onPlayerCreated(created)
      created.addListener(this@PlayerEngine)
    }

    if (player.playbackState == Player.STATE_IDLE) {
      if (!_isMediaBound || rebindMediaOnIdle) {
        runCatching {
          val uri = encodeUserInfoIfNeed(dataSource)
          val mediaItem = MediaItem.fromUri(uri)
          val startPositionMs = callback.consumeStartPositionMs()
          if (startPositionMs != C.TIME_UNSET) {
            player.setMediaItem(mediaItem, startPositionMs)
          } else {
            player.setMediaItem(mediaItem)
          }
          _isMediaBound = true
        }.onFailure { e ->
          setException(ComposePlayerException.DataSource(cause = e))
          return null
        }
      }
      player.prepare()
    }
    return player
  }

  /** 准备数据源并开始播放，若已处于播放结束状态则先回到起点 */
  fun startPlayer() {
    prepare()?.also { player ->
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0)
      }
      player.play()
    }
  }

  /** 停止底层播放并清空当前媒体项、异常与重试任务 */
  fun stopPlayer() {
    stopRetry()
    setException(null)
    _isMediaBound = false
    exoPlayer?.also { player ->
      if (player.playbackState != Player.STATE_IDLE) {
        player.stop()
      }
      player.clearMediaItems()
    }
    _bufferStateFlow.value = ComposePlayerBufferState.None
    setPlayerState(ComposePlayerState.Idle)
    callback.onStopped()
    notifyPositionChanged()
  }

  fun getCurrentPosition(): Long {
    if (!_isMediaBound) return 0L
    return (exoPlayer?.currentPosition ?: 0L).coerceAtLeast(0L)
  }

  fun setMute(mute: Boolean) {
    if (_isMutedFlow.value != mute) {
      _isMutedFlow.value = mute
      exoPlayer?.extSetMute(mute)
    }
  }

  fun setPlayerState(state: ComposePlayerState) {
    _playerStateFlow.value = state
  }

  fun setException(error: ComposePlayerException?) {
    _exceptionFlow.value = error
  }

  fun notifyPositionChanged() {
    _positionChangedFlow.tryEmit(Unit)
  }

  /** 调度延迟错误重试，配置未启用时返回 false */
  fun startRetry(action: () -> Unit): Boolean {
    if (retryOnErrorInterval <= 0) return false
    Runnable { action() }.also { job ->
      stopRetry()
      _retryJob = job
      handler.postDelayed(job, retryOnErrorInterval)
    }
    return true
  }

  fun stopRetry() {
    _retryJob?.also { job ->
      _retryJob = null
      handler.removeCallbacks(job)
    }
  }

  fun release() {
    stopPlayer()

    exoPlayer?.also { player ->
      exoPlayer = null
      player.removeListener(this)
      player.clearVideoSurface()
      player.release()
    }

    _dataSource = null
    _isMediaBound = false
    _videoSizeFlow.value = null
    _isRenderedFirstFrameFlow.value = false
    setException(null)
    _bufferStateFlow.value = ComposePlayerBufferState.None
    setPlayerState(ComposePlayerState.Idle)
    notifyPositionChanged()
  }

  /** 构建按需采样的播放进度冷流 */
  fun observePosition(interval: Duration): Flow<Long> {
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

  override fun onIsPlayingChanged(isPlaying: Boolean) {
    val player = exoPlayer
    callback.onIsPlayingChanged(player, isPlaying)
    notifyPositionChanged()
  }

  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
    if (!playWhenReady
      && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
      && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
    ) {
      callback.onPlayWhenReadyLost()
    }
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    _bufferStateFlow.value = when (playbackState) {
      Player.STATE_BUFFERING -> ComposePlayerBufferState.Buffering
      Player.STATE_READY -> ComposePlayerBufferState.Ready
      else -> ComposePlayerBufferState.None
    }

    when (playbackState) {
      Player.STATE_IDLE -> {
        setPlayerState(ComposePlayerState.Idle)
        _videoSizeFlow.value = null
        _isRenderedFirstFrameFlow.value = false
        callback.onStateIdle()
      }
      Player.STATE_READY -> {
        stopRetry()
        setException(null)
        exoPlayer?.also { callback.onStateReady(it) }
      }
      Player.STATE_ENDED -> {
        exoPlayer?.also { callback.onStateEnded(it) }
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
    exoPlayer?.also { callback.onTimelineOrMediaChanged(it) }
  }

  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    exoPlayer?.also { callback.onTimelineOrMediaChanged(it) }
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
    setException(error.toComposePlayerException())
    callback.onPlayerError(error)
  }

  interface Callback {
    fun createExoPlayer(context: Context): ExoPlayer
    fun consumeStartPositionMs(): Long {
      return C.TIME_UNSET
    }
    fun onPlayerCreated(player: ExoPlayer) {}
    fun onStopped() {}
    fun onIsPlayingChanged(player: ExoPlayer?, isPlaying: Boolean)
    fun onPlayWhenReadyLost() {}
    fun onStateIdle() {}
    fun onStateReady(player: ExoPlayer) {}
    fun onStateEnded(player: ExoPlayer)
    fun onTimelineOrMediaChanged(player: ExoPlayer) {}
    fun onPlayerError(error: PlaybackException)
  }
}

/** 提取 URI 中的用户名与密码并分别进行幂等百分比编码，兼容含 `@`、`#`、`?`、`/` 等特殊字符的密码 */
internal fun encodeUserInfoIfNeed(uri: String): String {
  if (uri.isEmpty()) return uri

  val schemeSeparator = uri.indexOf("://")
  if (schemeSeparator <= 0) return uri

  val userInfoStart = schemeSeparator + 3
  if (userInfoStart >= uri.length || uri[userInfoStart] == '/') return uri

  val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = userInfoStart)
    .let { if (it < 0) uri.length else it }
  val firstSegment = uri.substring(userInfoStart, authorityEnd)

  val atIndex = if ('@' in firstSegment) {
    val firstAtIndex = uri.lastIndexOf('@', startIndex = authorityEnd - 1)
    val candidateHostPort = uri.substring(firstAtIndex + 1, authorityEnd)
    if (isLikelyCompleteHostPort(candidateHostPort)) {
      firstAtIndex
    } else {
      val trailingAtIndex = findTruncatedUserInfoAtIndex(uri, startIndex = authorityEnd)
      if (trailingAtIndex >= 0) trailingAtIndex else firstAtIndex
    }
  } else {
    if (!looksLikeTruncatedUserInfo(firstSegment)) return uri
    findTruncatedUserInfoAtIndex(uri, startIndex = authorityEnd)
  }
  if (atIndex <= userInfoStart) return uri

  val rawUserInfo = uri.substring(userInfoStart, atIndex)
  if (rawUserInfo.isEmpty()) return uri

  val parts = rawUserInfo.split(":", limit = 2)
  val encodedUsername = percentEncodeComponent(parts[0])
  val encodedPassword = if (parts.size > 1) percentEncodeComponent(parts[1]) else null

  val newUserInfo = if (encodedPassword != null) {
    "$encodedUsername:$encodedPassword"
  } else {
    encodedUsername
  }
  if (newUserInfo == rawUserInfo) return uri

  return buildString(uri.length + 16) {
    append(uri, 0, userInfoStart)
    append(newUserInfo)
    append(uri, atIndex, uri.length)
  }
}

/** 判断被 `/`、`?`、`#` 截断的首段是否为未编码的 `user:password` 前缀（而非完整 `host:port`） */
private fun looksLikeTruncatedUserInfo(segment: String): Boolean {
  if (segment.isEmpty() || segment.startsWith("[")) return false
  val colonIndex = segment.indexOf(':')
  if (colonIndex <= 0) return false
  return !isLikelyCompleteHostPort(segment)
}

/** 从截断偏移后逆向查找紧跟合法 `host[:port]` 的 `@` 分隔符下标，排除含参数赋值或嵌套协议的普通 URL */
private fun findTruncatedUserInfoAtIndex(uri: String, startIndex: Int): Int {
  var searchFrom = uri.length - 1
  while (searchFrom >= startIndex) {
    val atIndex = uri.lastIndexOf('@', startIndex = searchFrom)
    if (atIndex < startIndex) return -1

    val middle = uri.substring(startIndex, atIndex)
    if ("=" !in middle && "://" !in middle) {
      val hostEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = atIndex + 1)
        .let { if (it < 0) uri.length else it }
      val hostPort = uri.substring(atIndex + 1, hostEnd)
      if (isValidHostPort(hostPort)) {
        return atIndex
      }
    }
    searchFrom = atIndex - 1
  }
  return -1
}

/** 判断 URI 片段是否为完整的主机（含域名点号、`localhost` 或 IPv6 字面量）及可选端口 */
private fun isLikelyCompleteHostPort(hostPort: String): Boolean {
  if (!isValidHostPort(hostPort)) return false
  if (hostPort.startsWith("[")) return true
  val host = hostPort.substringBefore(':')
  return '.' in host || host.equals("localhost", ignoreCase = true)
}

private fun isValidHostPort(hostPort: String): Boolean {
  if (hostPort.isEmpty()) return false
  if (hostPort.startsWith("[")) {
    val closeBracket = hostPort.indexOf(']')
    if (closeBracket <= 1) return false
    if (closeBracket == hostPort.length - 1) return true
    return hostPort[closeBracket + 1] == ':' && isValidPort(hostPort.substring(closeBracket + 2))
  }

  val colonIndex = hostPort.indexOf(':')
  val host = if (colonIndex >= 0) hostPort.substring(0, colonIndex) else hostPort
  if (host.isEmpty() || !host.all { it.isAsciiHostChar() }) return false

  if (colonIndex >= 0) {
    val portPart = hostPort.substring(colonIndex + 1)
    if (!isValidPort(portPart)) return false
  }
  return true
}

private fun isValidPort(port: String): Boolean {
  if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
  val value = port.toIntOrNull() ?: return false
  return value in 1..65535
}

/** 对凭据组件做幂等 RFC 3986 百分比编码，保留已有的合法 `%XX` 序列 */
private fun percentEncodeComponent(input: String): String {
  if (input.isEmpty()) return input
  val out = StringBuilder(input.length + 8)
  var i = 0
  while (i < input.length) {
    val ch = input[i]
    if (ch == '%' && i + 2 < input.length && input[i + 1].isHexDigit() && input[i + 2].isHexDigit()) {
      out.append('%')
      out.append(input[i + 1])
      out.append(input[i + 2])
      i += 3
    } else if (ch.isUnreservedUriChar()) {
      out.append(ch)
      i++
    } else {
      val codePointEnd = if (ch.isHighSurrogate() && i + 1 < input.length && input[i + 1].isLowSurrogate()) {
        i + 2
      } else {
        i + 1
      }
      val bytes = input.substring(i, codePointEnd).encodeToByteArray()
      for (b in bytes) {
        val unsigned = b.toInt() and 0xFF
        out.append('%')
        out.append(HEX_DIGITS[unsigned ushr 4])
        out.append(HEX_DIGITS[unsigned and 0x0F])
      }
      i = codePointEnd
    }
  }
  return out.toString()
}

private fun Char.isUnreservedUriChar(): Boolean {
  return (this in 'A'..'Z')
    || (this in 'a'..'z')
    || (this in '0'..'9')
    || this == '-'
    || this == '.'
    || this == '_'
    || this == '~'
}

private fun Char.isAsciiHostChar(): Boolean {
  return (this in 'A'..'Z')
    || (this in 'a'..'z')
    || (this in '0'..'9')
    || this == '-'
    || this == '.'
    || this == '_'
}

private fun Char.isHexDigit(): Boolean {
  return (this in '0'..'9') || (this in 'A'..'F') || (this in 'a'..'f')
}
