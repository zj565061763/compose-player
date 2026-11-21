package com.sd.lib.compose.player

import org.junit.Assert.assertEquals
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

    val localFileWithAt = "file:///storage/emulated/0/dir@name/demo.mp4"
    assertEquals(localFileWithAt, encodeUserInfoIfNeed(localFileWithAt))
  }
}
