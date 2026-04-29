package com.sd.demo.compose.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sd.demo.compose.player.theme.AppTheme
import com.sd.lib.compose.player.ComposePlayer
import com.sd.lib.compose.player.ComposePlayerBufferState
import com.sd.lib.compose.player.ComposePlayerException
import com.sd.lib.compose.player.ComposePlayerState
import com.sd.lib.compose.player.ComposePlayerView
import com.sd.lib.compose.player.desc
import com.sd.lib.compose.player.rememberComposePlayerRtsp

class SamplePlayerRtsp : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Content()
      }
    }
  }
}

private const val DATA_SOURCE = "rtsp://admin:camera@192.168.100.110:554/Streaming/Channels/102"

@Composable
private fun Content(
  modifier: Modifier = Modifier,
) {
  val player = rememberComposePlayerRtsp()
  val context = LocalContext.current

  var errorTips by remember { mutableStateOf("") }
  val playerState by player.playerStateFlow.collectAsStateWithLifecycle()
  val bufferState by player.bufferStateFlow.collectAsStateWithLifecycle()

  LaunchedEffect(player) {
    player.setDataSource(DATA_SOURCE)
    player.setCallback(object : ComposePlayer.Callback() {
      override fun onPlayerStateChanged(state: ComposePlayerState) {
        logMsg { "onPlayerStateChanged:$state" }
      }

      override fun onPlayerBufferStateChanged(state: ComposePlayerBufferState) {
        logMsg { "onPlayerBufferStateChanged:$state" }
        if (state == ComposePlayerBufferState.Ready) {
          errorTips = ""
        }
      }

      override fun onPlayerError(error: ComposePlayerException) {
        logMsg { "onPlayerError:${error.stackTraceToString()}" }
        errorTips = error.desc(context)
      }
    })
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .background(Color.Red),
      contentAlignment = Alignment.Center,
    ) {
      ComposePlayerView(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { scaleX = -1f },
        player = player,
      )
    }

    Text(text = playerState.name)
    Text(text = bufferState.name)

    if (errorTips.isNotEmpty()) {
      Text(text = errorTips, color = Color.Red)
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
    ) {
      Button(onClick = {
        player.play()
      }) {
        Text("play")
      }

      Button(onClick = {
        player.stop()
      }) {
        Text("stop")
      }
    }
  }
}