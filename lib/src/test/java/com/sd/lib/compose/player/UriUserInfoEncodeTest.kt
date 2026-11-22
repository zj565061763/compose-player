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
  fun encodesSpecialDelimitersInUsername() {
    assertEquals(
      "rtsp://user%231:pass@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://user#1:pass@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://user%2F1:pass@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://user/1:pass@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://user%3F1:pass@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://user?1:pass@192.168.1.100:554/live"),
    )
    assertEquals(
      "rtsp://user%231:p%40ss%232@192.168.1.100:554/live",
      encodeUserInfoIfNeed("rtsp://user#1:p@ss#2@192.168.1.100:554/live"),
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
  fun ignoresAtSignInPathQueryFragmentOrLocalFileUri() {
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

    val intranetFragmentWithAt = "http://intranet:8080#chapter@section"
    assertEquals(intranetFragmentWithAt, encodeUserInfoIfNeed(intranetFragmentWithAt))

    val intranetNestedProxyPathWithIp = "http://intranet:8080/proxy/cam@192.168.1.100/live.mp4"
    assertEquals(intranetNestedProxyPathWithIp, encodeUserInfoIfNeed(intranetNestedProxyPathWithIp))

    val credentialsAndFragmentWithAt = "https://user:pass@example.com#chapter@section"
    assertEquals(credentialsAndFragmentWithAt, encodeUserInfoIfNeed(credentialsAndFragmentWithAt))

    val credentialsAndProxyPathWithIp = "https://user:pass@example.com/proxy/cam@192.168.1.100/live.mp4"
    assertEquals(credentialsAndProxyPathWithIp, encodeUserInfoIfNeed(credentialsAndProxyPathWithIp))

    val explicitServerAndPathWithIp = "https://user:pass@192.168.1.1:8080/cam@192.168.1.100/live.mp4"
    assertEquals(explicitServerAndPathWithIp, encodeUserInfoIfNeed(explicitServerAndPathWithIp))

    val intranetWithCredsAndEmailInPath = "http://admin:pass@intranet:8080/users/test@domain.com/video.mp4"
    assertEquals(intranetWithCredsAndEmailInPath, encodeUserInfoIfNeed(intranetWithCredsAndEmailInPath))

    val rtspIntranetWithCredsAndProxyIp = "rtsp://admin:pass@camera01:554/proxy/cam@192.168.1.100/live"
    assertEquals(rtspIntranetWithCredsAndProxyIp, encodeUserInfoIfNeed(rtspIntranetWithCredsAndProxyIp))

    val intranetWithSpecialPasswordAndEmailInPath = "http://admin:p@ss@intranet:8080/users/test@domain.com/video.mp4"
    assertEquals(
      "http://admin:p%40ss@intranet:8080/users/test@domain.com/video.mp4",
      encodeUserInfoIfNeed(intranetWithSpecialPasswordAndEmailInPath),
    )

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
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtsp://[fe80::1:2]/live")))
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtsp://admin:p@ss#123@192.168.1.100:554/live")))
    assertTrue(isValidRtspUri(encodeUserInfoIfNeed("rtsp://user#1:pass@192.168.1.100:554/live")))

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
    assertFalse(isValidRtspUri("rtsp://[$$$]:554/live"))
    assertFalse(isValidRtspUri("rtsp://[1234]/live"))
    assertFalse(isValidRtspUri("rtsp://.../live"))
    assertFalse(isValidRtspUri("rtsp://192.168.1.999/live"))
    assertFalse(isValidRtspUri("rtsp://12345/live"))
  }
}
