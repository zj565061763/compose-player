package com.sd.lib.compose.player

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/** 播放器异常基类 */
sealed class ComposePlayerException(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause) {
  /** 数据源为空 */
  class DataSourceBlank internal constructor() : ComposePlayerException()

  /** 数据源异常 */
  class DataSource internal constructor(
    cause: Throwable? = null,
  ) : ComposePlayerException(cause = cause)

  /** 授权失败 */
  class Auth internal constructor(
    cause: Throwable? = null,
  ) : ComposePlayerException(cause = cause)

  /**
   * 播放异常
   *
   * @property errorCode 底层错误码
   * @property errorCodeName 底层错误码名称
   */
  class Playback internal constructor(
    val errorCode: Int,
    val errorCodeName: String,
    cause: Throwable? = null,
  ) : ComposePlayerException(
    message = "$errorCodeName($errorCode)",
    cause = cause,
  )
}

/** 获取本地化异常描述 */
fun ComposePlayerException.desc(context: Context): String {
  return when (this) {
    is ComposePlayerException.DataSourceBlank -> {
      context.getString(R.string.lib_compose_player_ComposePlayerExceptionDataSourceBlank)
    }

    is ComposePlayerException.DataSource -> {
      buildString {
        append(context.getString(R.string.lib_compose_player_ComposePlayerExceptionDataSource))
        if (cause != null) append(":").append(cause)
      }
    }

    is ComposePlayerException.Auth -> {
      context.getString(R.string.lib_compose_player_ComposePlayerExceptionAuth)
    }

    is ComposePlayerException.Playback -> {
      buildString {
        append("(").append(errorCode).append(")")
        val detail = cause?.toString() ?: errorCodeName
        if (detail.isNotEmpty()) append(detail)
      }
    }
  }
}

/** 将底层[PlaybackException]包装为[ComposePlayerException] */
internal fun PlaybackException.toComposePlayerException(): ComposePlayerException {
  return when {
    isAuthError() -> ComposePlayerException.Auth(cause = cause)
    else -> ComposePlayerException.Playback(
      errorCode = errorCode,
      errorCodeName = errorCodeName,
      cause = cause,
    )
  }
}

private val RTSP_AUTH_STATUS_REGEX = Regex("^[A-Z_]+ (401|403)$")
private val PARSER_AUTH_RESPONSE_REGEX = Regex("\\b(401|403)\\s+response\\b", RegexOption.IGNORE_CASE)

/** 判断异常链中是否包含 HTTP 或 RTSP 鉴权失败（401/403） */
@SuppressLint("UnsafeOptInUsageError")
internal fun Throwable?.isAuthError(): Boolean {
  var current = this
  while (current != null) {
    when (current) {
      is HttpDataSource.InvalidResponseCodeException -> {
        if (current.responseCode == 401 || current.responseCode == 403) {
          return true
        }
      }
      is RtspMediaSource.RtspPlaybackException -> {
        val msg = current.message?.trim()
        if (msg != null && RTSP_AUTH_STATUS_REGEX.matches(msg)) {
          return true
        }
      }
      is ParserException -> {
        if (PARSER_AUTH_RESPONSE_REGEX.containsMatchIn(current.message)) {
          return true
        }
      }
    }
    current = current.cause
  }
  return false
}
