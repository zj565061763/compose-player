package com.sd.lib.compose.player

import android.content.Context
import android.content.res.Configuration
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.ConnectException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AuthErrorDetectTest {
  @Test
  fun detectsHttp401And403Only() {
    assertTrue(newTestHttpResponseException(401).isAuthError())
    assertTrue(newTestHttpResponseException(403).isAuthError())
    assertFalse(newTestHttpResponseException(404).isAuthError())
    assertFalse(newTestHttpResponseException(500).isAuthError())
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
    val authConverted = newTestPlaybackException(
      errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      cause = authCause,
    ).toComposePlayerException()
    assertTrue(authConverted is ComposePlayerException.Auth)
    assertSame(authCause, authConverted.cause)

    val ioCause = ConnectException("Connection refused")
    val playbackConverted = newTestPlaybackException(
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

  @Test
  fun formatsLocalizedExceptionDescriptionsAcrossSupportedLocales() {
    val baseContext = ApplicationProvider.getApplicationContext<Context>()
    val zhCnContext = baseContext.withLocale(Locale.SIMPLIFIED_CHINESE)
    val zhTwContext = baseContext.withLocale(Locale.TRADITIONAL_CHINESE)
    val enContext = baseContext.withLocale(Locale.ENGLISH)

    val blankError = ComposePlayerException.DataSourceBlank()
    assertEquals("数据源为空", blankError.desc(zhCnContext))
    assertEquals("數據源為空", blankError.desc(zhTwContext))
    assertEquals("data source is empty", blankError.desc(enContext))

    val dataSourceNoCause = ComposePlayerException.DataSource()
    assertEquals("数据源异常", dataSourceNoCause.desc(zhCnContext))

    val illegalArg = IllegalArgumentException("Invalid RTSP URI")
    val dataSourceWithCause = ComposePlayerException.DataSource(cause = illegalArg)
    assertEquals("data source error:$illegalArg", dataSourceWithCause.desc(enContext))

    val authError = ComposePlayerException.Auth()
    assertEquals("授权失败", authError.desc(zhCnContext))
    assertEquals("授權失敗", authError.desc(zhTwContext))
    assertEquals("authorization failed", authError.desc(enContext))

    val ioCause = IOException("timeout")
    val playbackWithCause = ComposePlayerException.Playback(
      errorCode = 2001,
      errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
      cause = ioCause,
    )
    assertEquals("(2001)$ioCause", playbackWithCause.desc(enContext))

    val playbackWithoutCause = ComposePlayerException.Playback(
      errorCode = 2001,
      errorCodeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
      cause = null,
    )
    assertEquals("(2001)ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", playbackWithoutCause.desc(enContext))
  }
}

private fun Context.withLocale(locale: Locale): Context {
  val configuration = Configuration(resources.configuration)
  configuration.setLocale(locale)
  return createConfigurationContext(configuration)
}
