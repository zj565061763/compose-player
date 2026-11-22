package com.sd.lib.compose.player

private const val HEX_DIGITS = "0123456789ABCDEF"

/** 提取 URI 中的用户名与密码并分别进行幂等百分比编码，兼容含 `@`、`#`、`?`、`/`、`=` 等特殊字符的凭据 */
internal fun encodeUserInfoIfNeed(uri: String): String {
  if (uri.isEmpty()) return uri

  val schemeSeparator = uri.indexOf("://")
  if (schemeSeparator <= 0) return uri

  val userInfoStart = schemeSeparator + 3
  if (userInfoStart >= uri.length || uri[userInfoStart] == '/') return uri

  val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = userInfoStart)
    .let { if (it < 0) uri.length else it }
  val firstSegment = uri.substring(userInfoStart, authorityEnd)
  if ('@' !in firstSegment && isDefiniteHostPort(firstSegment)) {
    return uri
  }

  val atIndex = findUserInfoAtIndex(
    uri = uri,
    startIndex = userInfoStart,
    authorityEnd = authorityEnd,
  )
  if (atIndex <= userInfoStart) return uri

  val rawUserInfo = uri.substring(userInfoStart, atIndex)
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

/** 校验百分比编码后的 URI 是否为合法的 RTSP/RTSPT 地址 */
internal fun isValidRtspUri(encodedUri: String): Boolean {
  if (encodedUri.isEmpty() || encodedUri.any { it.isWhitespace() }) return false

  val schemeSeparator = encodedUri.indexOf("://")
  if (schemeSeparator <= 0) return false

  val scheme = encodedUri.substring(0, schemeSeparator)
  if (!scheme.equals("rtsp", ignoreCase = true) && !scheme.equals("rtspt", ignoreCase = true)) {
    return false
  }

  val authorityStart = schemeSeparator + 3
  if (authorityStart >= encodedUri.length) return false

  val authorityEnd = encodedUri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
    .let { if (it < 0) encodedUri.length else it }
  val authority = encodedUri.substring(authorityStart, authorityEnd)
  if (authority.isEmpty()) return false

  val atIndex = authority.lastIndexOf('@')
  val hostPort = if (atIndex >= 0) {
    if (atIndex == 0 || '@' in authority.substring(0, atIndex)) return false
    authority.substring(atIndex + 1)
  } else {
    authority
  }
  return isValidHostPort(hostPort)
}

/** 判断不含 `@` 的首段是否为明确的无凭据主机端点（域名、`localhost` 或 IPv6 字面量） */
private fun isDefiniteHostPort(segment: String): Boolean {
  if (!isValidHostPort(segment)) return false
  if (segment.startsWith("[")) return true
  val host = segment.substringBefore(':')
  return '.' in host || host.equals("localhost", ignoreCase = true)
}

/** 定位凭据末尾的 `@` 下标：首段含合法主机时直接采用，被 `/`、`?`、`#` 截断时向后查找服务器端点 */
private fun findUserInfoAtIndex(
  uri: String,
  startIndex: Int,
  authorityEnd: Int,
): Int {
  val atInAuthority = uri.lastIndexOf('@', startIndex = authorityEnd - 1)
  if (atInAuthority > startIndex) {
    val hostInAuthority = uri.substring(atInAuthority + 1, authorityEnd)
    if (isDefiniteHostPort(hostInAuthority) || ':' in hostInAuthority) {
      return atInAuthority
    }
  }

  var searchFrom = uri.length - 1
  while (searchFrom >= authorityEnd) {
    val atIndex = uri.lastIndexOf('@', startIndex = searchFrom)
    if (atIndex < authorityEnd) break

    val hostEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), startIndex = atIndex + 1)
      .let { if (it < 0) uri.length else it }
    val hostPort = uri.substring(atIndex + 1, hostEnd)
    if (isValidHostPort(hostPort)
      && isServerEndpoint(hostPort)
      && "://" !in uri.substring(startIndex, atIndex)
    ) {
      return atIndex
    }
    searchFrom = atIndex - 1
  }
  return if (atInAuthority > startIndex) atInAuthority else -1
}

/** 判断是否为带端口或 IP 的服务器端点 */
private fun isServerEndpoint(hostPort: String): Boolean {
  return hostPort.startsWith("[") || ':' in hostPort || isIpv4Address(hostPort)
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

private fun isValidHostPort(hostPort: String): Boolean {
  if (hostPort.isEmpty()) return false
  if (hostPort.startsWith("[")) {
    val closeBracket = hostPort.indexOf(']')
    if (closeBracket <= 1) return false
    val ipv6 = hostPort.substring(1, closeBracket)
    if (!isValidIpv6Literal(ipv6)) return false
    if (closeBracket == hostPort.length - 1) return true
    return hostPort[closeBracket + 1] == ':' && isValidPort(hostPort.substring(closeBracket + 2))
  }

  val colonIndex = hostPort.indexOf(':')
  val host = if (colonIndex >= 0) hostPort.substring(0, colonIndex) else hostPort
  if (!isValidRegHost(host)) return false

  if (colonIndex >= 0) {
    val portPart = hostPort.substring(colonIndex + 1)
    if (!isValidPort(portPart)) return false
  }
  return true
}

private fun isValidIpv6Literal(ipv6: String): Boolean {
  if (':' !in ipv6 || ":::" in ipv6) return false
  if ((ipv6.startsWith(':') && !ipv6.startsWith("::"))
    || (ipv6.endsWith(':') && !ipv6.endsWith("::"))
  ) {
    return false
  }
  return ipv6.all { it.isHexDigit() || it == ':' || it == '.' }
}

private fun isValidRegHost(host: String): Boolean {
  if (host.isEmpty() || !host.all { it.isAsciiHostChar() }) return false
  if (host.startsWith('.') || host.endsWith('.') || ".." in host) return false
  if (host.startsWith('-') || host.endsWith('-')) return false
  if (host.all { it in '0'..'9' || it == '.' }) {
    return isIpv4Address(host)
  }
  return host.any { (it in 'A'..'Z') || (it in 'a'..'z') }
}

private fun isValidPort(port: String): Boolean {
  if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
  val value = port.toIntOrNull() ?: return false
  return value in 1..65535
}

private fun isIpv4Address(host: String): Boolean {
  val parts = host.split('.')
  if (parts.size != 4) return false
  return parts.all { part ->
    part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } && (part.toIntOrNull() ?: -1) in 0..255
  }
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
