package com.sd.lib.compose.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberComposePlayer(
  init: ComposePlayer.() -> Unit = {},
): ComposePlayer {
  val context = LocalContext.current
  return remember { ComposePlayer.create(context).apply(init) }.also { player ->
    DisposableEffect(player) {
      onDispose { player.release() }
    }
  }
}

@Composable
fun rememberComposePlayerRtsp(
  init: ComposePlayer.() -> Unit = {},
): ComposePlayer {
  val context = LocalContext.current
  return remember { ComposePlayer.createRtsp(context).apply(init) }.also { player ->
    DisposableEffect(player) {
      onDispose { player.release() }
    }
  }
}