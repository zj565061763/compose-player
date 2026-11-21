package com.sd.lib.compose.player

import android.os.Bundle
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException

class AuthErrorDetectTest {
  @Test
  fun detectsHttp401And403Only() {
    assertTrue(newHttpResponseException(401).isAuthError())
    assertTrue(newHttpResponseException(403).isAuthError())
    assertFalse(newHttpResponseException(404).isAuthError())
    assertFalse(newHttpResponseException(500).isAuthError())
  }

  @Test
  fun detectsRtspAuthErrorsWithoutFalsePositivesOnChannelOrPort() {
    assertTrue(RtspMediaSource.RtspPlaybackException("DESCRIBE 401").isAuthError())
    assertTrue(RtspMediaSource.RtspPlaybackException("SETUP 403").isAuthError())
    assertFalse(RtspMediaSource.RtspPlaybackException("DESCRIBE 404").isAuthError())

    val missingHeader = RtspMediaSource.RtspPlaybackException(
      ParserException.createForMalformedManifest(
        "Missing WWW-Authenticate header in a 401 response.",
        null,
      ),
    )
    assertTrue(missingHeader.isAuthError())

    val nullMessageParserError = ParserException.createForMalformedManifest(null, null)
    assertFalse(nullMessageParserError.isAuthError())

    val channelUrlTimeout = RtspMediaSource.RtspPlaybackException(
      ConnectException("Failed to connect to rtsp://192.168.1.100:8401/Streaming/Channels/401"),
    )
    assertFalse(channelUrlTimeout.isAuthError())

    val genericIoWith403InUrl = IOException("Connection reset: https://cdn.example.com/20260403/video_401.mp4")
    assertFalse(genericIoWith403InUrl.isAuthError())
  }

  @Test
  fun preservesCauseWhenConvertingAuthAndPlaybackExceptions() {
    val authCause = RtspMediaSource.RtspPlaybackException("DESCRIBE 401")
    val authConverted = newPlaybackException(
      errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      cause = authCause,
    ).toComposePlayerException()
    assertTrue(authConverted is ComposePlayerException.Auth)
    assertSame(authCause, authConverted.cause)

    val ioCause = ConnectException("Connection refused")
    val playbackConverted = newPlaybackException(
      errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      cause = ioCause,
    ).toComposePlayerException()
    assertTrue(playbackConverted is ComposePlayerException.Playback)
    assertEquals(
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      (playbackConverted as ComposePlayerException.Playback).errorCode,
    )
    assertSame(ioCause, playbackConverted.cause)
  }

  private fun newPlaybackException(
    errorCode: Int,
    cause: Throwable?,
  ): PlaybackException {
    return object : PlaybackException(null, cause, errorCode, Bundle.EMPTY, 0L) {}
  }

  private fun newHttpResponseException(code: Int): HttpDataSource.InvalidResponseCodeException {
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
}
