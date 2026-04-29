package com.sd.lib.compose.player

import android.content.Context
import androidx.media3.common.PlaybackException

/** 异常描述 */
fun ComposePlayerException.desc(context: Context): String {
  return when (this) {
    is ComposePlayerExceptionDataSourceBlank -> {
      context.getString(R.string.lib_compose_player_ComposePlayerExceptionDataSourceBlank)
    }

    is ComposePlayerExceptionDataSource -> {
      buildString {
        append(context.getString(R.string.lib_compose_player_ComposePlayerExceptionDataSource))
        if (cause != null) append(":").append(cause)
      }
    }

    is ComposePlayerExceptionAuth -> {
      context.getString(R.string.lib_compose_player_ComposePlayerExceptionAuth)
    }

    is ComposePlayerExceptionPlaybackException -> {
      buildString {
        append("(${exception.errorCode})")
        exception.cause?.also { cause -> append(cause) }
      }
    }

    else -> this.toString()
  }
}

open class ComposePlayerException internal constructor(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** 数据源为空 */
class ComposePlayerExceptionDataSourceBlank internal constructor() : ComposePlayerException()

/** 数据源异常 */
class ComposePlayerExceptionDataSource internal constructor(
  cause: Throwable? = null,
) : ComposePlayerException(cause = cause)

/** 授权失败 */
class ComposePlayerExceptionAuth internal constructor() : ComposePlayerException()

/** [PlaybackException]包装 */
class ComposePlayerExceptionPlaybackException internal constructor(
  val exception: PlaybackException,
) : ComposePlayerException()

/** 是否授权错误 */
internal fun Throwable?.isAuthError(): Boolean {
  if (this == null) return false

  val message = this.message
  if (message != null && (message.contains("401") || message.contains("403"))) {
    return true
  }

  return this.cause.isAuthError()
}