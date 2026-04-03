package com.sd.lib.compose.player

import androidx.media3.common.PlaybackException

open class ComposePlayerException internal constructor(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** 数据源异常 */
class ComposePlayerExceptionDataSource internal constructor(
  cause: Throwable? = null,
) : ComposePlayerException(cause = cause) {
  override fun toString(): String {
    return buildString {
      append("DataSource error")
      cause?.also { cause -> append(":").append(cause) }
    }
  }
}

internal class ComposePlayerExceptionPlaybackException(
  private val exception: PlaybackException,
) : ComposePlayerException() {
  override fun toString(): String {
    return buildString {
      append("(${exception.errorCode})")
      exception.cause?.also { cause -> append(cause) }
    }
  }
}