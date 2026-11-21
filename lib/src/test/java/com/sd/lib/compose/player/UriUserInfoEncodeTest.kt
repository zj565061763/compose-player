package com.sd.lib.compose.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriUserInfoEncodeTest {
  @Test
  fun plainCredentialsRemainUnchanged() {
    val uri = "rtsp://admin:camera@192.168.100.110:554/Streaming/Channels/102"
    assertEquals(uri, encodeUserInfoIfNeed(uri))
  }

  @Test
  fun encodesAtAndColonInPassword() {
    val uri = "rtsp://admin:p@ss:123@192.168.100.110:554/Streaming/Channels/102"
    assertEquals(
      "rtsp://admin:p%40ss%3A123@192.168.100.110:554/Streaming/Channels/102",
      encodeUserInfoIfNeed(uri),
    )
  }

  @Test
  fun encodesHashQuestionAndSlashInPassword() {
    assertEquals(
      "rtsp://admin:Admin%23123@192.168.1.100:554/Streaming/Channels/101",
      encodeUserInfoIfNeed("rtsp://admin:Admin#123@192.168.1.100:554/Streaming/Channels/101"),
    )
    assertEquals(
      "rtsp://admin:pass%3F123@192.168.1.100:554/Streaming/Channels/101",
      encodeUserInfoIfNeed("rtsp://admin:pass?123@192.168.1.100:554/Streaming/Channels/101"),
    )
    assertEquals(
      "rtsp://admin:pass%2F123@192.168.1.100:554/Streaming/Channels/101",
      encodeUserInfoIfNeed("rtsp://admin:pass/123@192.168.1.100:554/Streaming/Channels/101"),
    )
    assertEquals(
      "rtsp://admin:p%2F1%402%233%3F4@192.168.1.100/live",
      encodeUserInfoIfNeed("rtsp://admin:p/1@2#3?4@192.168.1.100/live"),
    )
  }

  @Test
  fun encodesNumericPrefixAndAtBeforeDelimiterInPassword() {
    assertEquals(
      "rtsp://admin:2024%23abc@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:2024#abc@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:1234%2Fabc@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:1234/abc@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:p%40ss%23123@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:p@ss#123@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:p%40a.b%231@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:p@a.b#1@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:p%40a.b%2F1@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:p@a.b/1@192.168.1.100:554/live"),
    )
  }

  @Test
  fun encodesEqualsWithDelimiterInPassword() {
    assertEquals(
      "rtsp://admin:pass%23a%3Db@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:pass#a=b@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:pass%2Fa%3Db@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:pass/a=b@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://admin:pass%3Fa%3Db@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:pass?a=b@192.168.1.100:554/live"),
    )
  }

  @Test
  fun idempotentForAlreadyEncodedCredentialsAndEncodedColonInUsername() {
    val alreadyEncoded = "rtsp://domain%3Aadmin:p%40ss%23123@192.168.1.100:554/live"
    assertEquals(alreadyEncoded, encodeUserInfoIfNeed(alreadyEncoded))
  }

  @Test
  fun encodesBarePercentWithoutBreakingValidEscapes() {
    assertEquals(
      "rtsp://admin:100%25ok%401@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://admin:100%ok%401@192.168.1.100:554/live"),
    )
  }

  @Test
  fun ignoresAtSignInPathQueryOrLocalFileUri() {
    val httpWithEmailInPath = "https://example.com/users/test@domain.com/video.mp4"
    assertEquals(httpWithEmailInPath, encodeUserInfoIfNeed(httpWithEmailInPath))

    val httpWithPortAndQueryAt = "https://example.com:8080/play?token=abc:def@123"
    assertEquals(httpWithPortAndQueryAt, encodeUserInfoIfNeed(httpWithPortAndQueryAt))

    val localhostWithEmailInPath = "http://localhost:8080/users/test@domain.com/video.mp4"
    assertEquals(localhostWithEmailInPath, encodeUserInfoIfNeed(localhostWithEmailInPath))

    val intranetPathWithEmail = "http://intranet:8080/users/test@domain.com/video.mp4"
    assertEquals(intranetPathWithEmail, encodeUserInfoIfNeed(intranetPathWithEmail))

    val intranetQueryWithEmail = "http://intranet:8080/play?mail=test@domain.com"
    assertEquals(intranetQueryWithEmail, encodeUserInfoIfNeed(intranetQueryWithEmail))

    val credentialsAndEmailInPath = "https://admin:p@ss@example.com/users/test@domain.com/video.mp4"
    assertEquals(
      "https://admin:p%40ss@example.com/users/test@domain.com/video.mp4",
      encodeUserInfoIfNeed(credentialsAndEmailInPath),
    )

    val localFileWithAt = "file:///storage/emulated/0/dir@name/demo.mp4"
    assertEquals(localFileWithAt, encodeUserInfoIfNeed(localFileWithAt))
  }

  @Test
  fun validatesRtspUrisAccurately() {
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtsp://192.168.1.100:554/Streaming/Channels/101")))
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtspt://camera_01.local/live")))
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("RTSP://[::1]:8554/live")))
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtsp://admin:p@ss#123@192.168.1.100:554/live")))

    assertFalse(isValidRtspUri(""))
    assertFalse(isValidRtspUri("192.168.1.100:554/live"))
    assertFalse(isValidRtspUri("rtsp://"))
    assertFalse(isValidRtspUri("rtsp:///live"))
    assertFalse(isValidRtspUri("rtsp://admin:123@"))
    assertFalse(isValidRtspUri("rtsp://admin:123@/live"))
    assertFalse(isValidRtspUri("rtsp://@192.168.1.100/live"))
    assertFalse(isValidRtspUri("http://192.168.1.100:554/live"))
    assertFalse(isValidRtspUri("android.resource:/213123456"))
    assertFalse(isValidRtspUri("rtsp://192.168.1.100:0/live"))
    assertFalse(isValidRtspUri("rtsp://192.168.1.100:70000/live"))
    assertFalse(isValidRtspUri("rtsp://192.168.1.100:abc/live"))
    assertFalse(isValidRtspUri("rtsp://192.168.1.100/live stream"))
  }
}
