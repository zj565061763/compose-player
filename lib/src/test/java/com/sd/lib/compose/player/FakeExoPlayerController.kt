package com.sd.lib.compose.player

import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
  val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
  override fun starting(description: Description) {
    Dispatchers.setMain(testDispatcher)
  }

  override fun finished(description: Description) {
    Dispatchers.resetMain()
  }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class FakeExoPlayerController {
  private val _listeners = CopyOnWriteArrayList<Player.Listener>()
  private var _deferredFlagsBuilder = FlagSet.Builder()

  var deferOnEvents: Boolean = false
  var keepStaleVideoSizeOnStop: Boolean = false

  var playbackState: Int = Player.STATE_IDLE
    private set
  var playWhenReady: Boolean = false
    private set
  var playbackSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private set
  var playerError: ExoPlaybackException? = null
    private set

  var currentPosition: Long = 0L
  var bufferedPosition: Long = 0L
  var duration: Long = C.TIME_UNSET
  var videoSize: VideoSize = VideoSize.UNKNOWN
  var surfaceSize: Size = Size.UNKNOWN
  var videoDecoderCounters: DecoderCounters? = null

  var volume: Float = 1f
  var volumeSetCount: Int = 0
    private set
  var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
  var playbackParametersSetCount: Int = 0
    private set
  var repeatMode: Int = Player.REPEAT_MODE_OFF
  var repeatModeSetCount: Int = 0
    private set

  val mediaItems: MutableList<MediaItem> = mutableListOf()
  var setMediaItemCount: Int = 0
    private set
  var lastStartPositionMs: Long = C.TIME_UNSET
    private set
  var prepareCount: Int = 0
    private set
  var stopCount: Int = 0
    private set
  var clearMediaItemsCount: Int = 0
    private set
  var clearVideoSurfaceCount: Int = 0
    private set
  var seekToDefaultPositionCount: Int = 0
    private set
  val seekToHistory: MutableList<Long> = mutableListOf()
  var isReleased: Boolean = false
    private set

  val isPlaying: Boolean
    get() {
      return playbackState == Player.STATE_READY
        && playWhenReady
        && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }

  val exoPlayer: ExoPlayer = Proxy.newProxyInstance(
    ExoPlayer::class.java.classLoader,
    arrayOf(ExoPlayer::class.java),
  ) { proxy, method, rawArgs ->
    invokeProxyMethod(proxy, method.name, method.returnType, rawArgs ?: emptyArray())
  } as ExoPlayer

  fun moveToBuffering() {
    updateState {
      playerError = null
      playbackState = Player.STATE_BUFFERING
    }
  }

  fun moveToReady(
    durationMs: Long = duration,
    size: VideoSize = videoSize,
  ) {
    updateState {
      playerError = null
      duration = durationMs
      videoSize = size
      playbackState = Player.STATE_READY
    }
  }

  fun renderFirstFrame() {
    updateState(extraEvents = intArrayOf(Player.EVENT_RENDERED_FIRST_FRAME)) {}
  }

  fun updateVideoSize(size: VideoSize) {
    updateState(extraEvents = intArrayOf(Player.EVENT_VIDEO_SIZE_CHANGED)) {
      videoSize = size
    }
  }

  private fun invokeProxyMethod(
    proxy: Any,
    methodName: String,
    returnType: Class<*>,
    args: Array<out Any?>,
  ): Any? {
    when (methodName) {
      "equals" -> return proxy === args.firstOrNull()
      "hashCode" -> return System.identityHashCode(proxy)
      "toString" -> return "FakeExoPlayer"

      "getPlaybackState" -> return playbackState
      "getPlayWhenReady" -> return playWhenReady
      "isPlaying" -> return isPlaying
      "getPlaybackSuppressionReason" -> return playbackSuppressionReason
      "getPlayerError" -> return playerError
      "getCurrentPosition" -> return currentPosition
      "getBufferedPosition" -> return bufferedPosition
      "getDuration" -> return duration
      "getVideoSize" -> return videoSize
      "getSurfaceSize" -> return surfaceSize
      "getVideoDecoderCounters" -> return videoDecoderCounters
      "getVolume" -> return volume
      "getPlaybackParameters" -> return playbackParameters
      "getRepeatMode" -> return repeatMode

      "addListener" -> _listeners.add(args[0] as Player.Listener)
      "removeListener" -> _listeners.remove(args[0] as Player.Listener)
      "setPlayWhenReady" -> {
        val target = args[0] as Boolean
        updateState {
          playWhenReady = target
        }
      }
      "play" -> {
        updateState {
          playWhenReady = true
        }
      }
      "pause" -> {
        updateState {
          playWhenReady = false
        }
      }
      "setVolume" -> {
        volume = args[0] as Float
        volumeSetCount++
      }
      "setPlaybackParameters" -> {
        val target = args[0] as PlaybackParameters
        playbackParametersSetCount++
        updateState {
          playbackParameters = target
        }
      }
      "setRepeatMode" -> {
        repeatMode = args[0] as Int
        repeatModeSetCount++
      }
      "setMediaItem" -> {
        val item = args[0] as MediaItem
        mediaItems.clear()
        mediaItems.add(item)
        setMediaItemCount++
        val startPos = if (args.size >= 2 && args[1] is Long) {
          args[1] as Long
        } else {
          C.TIME_UNSET
        }
        lastStartPositionMs = startPos
        currentPosition = if (startPos != C.TIME_UNSET) startPos else 0L
      }
      "prepare" -> {
        prepareCount++
        if (playbackState == Player.STATE_IDLE) {
          updateState {
            playerError = null
            playbackState = if (mediaItems.isEmpty()) Player.STATE_ENDED else Player.STATE_BUFFERING
          }
        }
      }
      "seekTo" -> {
        if (args.size == 1 && args[0] is Long) {
          val pos = args[0] as Long
          seekToHistory.add(pos)
          updateState(extraEvents = intArrayOf(Player.EVENT_POSITION_DISCONTINUITY)) {
            currentPosition = pos
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
              playbackState = Player.STATE_BUFFERING
            }
          }
        }
      }
      "seekToDefaultPosition" -> {
        if (args.isEmpty()) {
          seekToDefaultPositionCount++
          updateState(extraEvents = intArrayOf(Player.EVENT_POSITION_DISCONTINUITY)) {
            currentPosition = 0L
          }
        }
      }
      "stop" -> {
        stopCount++
        if (playbackState != Player.STATE_IDLE) {
          updateState {
            playerError = null
            if (!keepStaleVideoSizeOnStop) {
              videoSize = VideoSize.UNKNOWN
            }
            playbackState = Player.STATE_IDLE
          }
        }
      }
      "clearMediaItems" -> {
        clearMediaItemsCount++
        mediaItems.clear()
      }
      "clearVideoSurface" -> clearVideoSurfaceCount++
      "release" -> {
        isReleased = true
        _listeners.clear()
      }
      else -> return defaultReturnValue(returnType)
    }
    return null
  }

  fun moveToEnded() {
    updateState {
      if (duration != C.TIME_UNSET && duration > 0) {
        currentPosition = duration
      }
      playbackState = Player.STATE_ENDED
    }
  }

  fun failWith(error: ExoPlaybackException) {
    updateState(extraEvents = intArrayOf(Player.EVENT_PLAYER_ERROR)) {
      playerError = error
      videoSize = VideoSize.UNKNOWN
      playbackState = Player.STATE_IDLE
    }
  }

  fun losePlayWhenReady(reason: Int) {
    updateState(playWhenReadyReason = reason) {
      playWhenReady = false
    }
  }

  fun updateSuppressionReason(reason: Int) {
    updateState {
      playbackSuppressionReason = reason
    }
  }

  fun notifyPositionDiscontinuity(newPositionMs: Long) {
    updateState(extraEvents = intArrayOf(Player.EVENT_POSITION_DISCONTINUITY)) {
      currentPosition = newPositionMs
    }
  }

  private fun updateState(
    playWhenReadyReason: Int = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
    extraEvents: IntArray = intArrayOf(),
    mutation: () -> Unit,
  ) {
    val prevState = playbackState
    val prevPlayWhenReady = playWhenReady
    val prevSuppression = playbackSuppressionReason
    val prevError = playerError
    val prevIsPlaying = isPlaying
    val prevVideoSize = videoSize
    val prevParams = playbackParameters

    mutation()

    val flagBuilder = FlagSet.Builder()
    if (prevError !== playerError) {
      flagBuilder.add(Player.EVENT_PLAYER_ERROR)
    }
    if (prevState != playbackState) {
      flagBuilder.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }
    if (prevPlayWhenReady != playWhenReady) {
      flagBuilder.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
    }
    if (prevSuppression != playbackSuppressionReason) {
      flagBuilder.add(Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)
    }
    if (prevIsPlaying != isPlaying) {
      flagBuilder.add(Player.EVENT_IS_PLAYING_CHANGED)
    }
    if (prevVideoSize != videoSize) {
      flagBuilder.add(Player.EVENT_VIDEO_SIZE_CHANGED)
    }
    if (prevParams != playbackParameters) {
      flagBuilder.add(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)
    }
    for (event in extraEvents) {
      flagBuilder.add(event)
    }

    if (prevPlayWhenReady != playWhenReady) {
      for (listener in _listeners) {
        listener.onPlayWhenReadyChanged(playWhenReady, playWhenReadyReason)
      }
    }

    val flags = flagBuilder.build()
    if (flags.size() > 0) {
      if (deferOnEvents) {
        _deferredFlagsBuilder.addAll(flags)
      } else {
        val events = Player.Events(flags)
        for (listener in _listeners) {
          listener.onEvents(exoPlayer, events)
        }
      }
    }
  }

  fun flushDeferredEvents() {
    val flags = _deferredFlagsBuilder.build()
    _deferredFlagsBuilder = FlagSet.Builder()
    if (flags.size() > 0) {
      val events = Player.Events(flags)
      for (listener in _listeners) {
        listener.onEvents(exoPlayer, events)
      }
    }
  }
}

internal inline fun <T : ComposePlayerCore> T.usePlayer(block: (T) -> Unit) {
  try {
    block(this)
  } finally {
    release()
  }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun newTestPlaybackException(
  errorCode: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
  cause: IOException = IOException("test io error"),
): ExoPlaybackException {
  return ExoPlaybackException.createForSource(cause, errorCode)
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun newTestHttpResponseException(code: Int): HttpDataSource.InvalidResponseCodeException {
  val constructor = HttpDataSource.InvalidResponseCodeException::class.java.constructors.first {
    it.parameterCount == 6
  }
  return constructor.newInstance(
    code,
    null,
    null,
    emptyMap<String, List<String>>(),
    null,
    ByteArray(0),
  ) as HttpDataSource.InvalidResponseCodeException
}

private fun defaultReturnValue(returnType: Class<*>): Any? {
  return when (returnType) {
    Boolean::class.javaPrimitiveType -> false
    Int::class.javaPrimitiveType -> 0
    Long::class.javaPrimitiveType -> 0L
    Float::class.javaPrimitiveType -> 0f
    Double::class.javaPrimitiveType -> 0.0
    Byte::class.javaPrimitiveType -> 0.toByte()
    Short::class.javaPrimitiveType -> 0.toShort()
    Char::class.javaPrimitiveType -> 0.toChar()
    else -> null
  }
}
