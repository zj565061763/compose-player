package com.sd.demo.compose.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.sd.lib.compose.player.ComposePlayerException
import com.sd.lib.compose.player.ComposePlayerRtsp
import com.sd.lib.compose.player.ComposePlayerState
import com.sd.lib.compose.player.ComposePlayerView
import com.sd.lib.compose.player.desc
import com.sd.lib.compose.player.rememberComposePlayerRtsp
import kotlinx.coroutines.delay

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

  val playerState by player.playerStateFlow.collectAsStateWithLifecycle()
  val bufferState by player.bufferStateFlow.collectAsStateWithLifecycle()
  val videoSize by player.videoSizeFlow.collectAsStateWithLifecycle()
  val exception by player.exceptionFlow.collectAsStateWithLifecycle()

  LaunchedEffect(player) {
    player.setDataSource(DATA_SOURCE)
    player.setCallback(object : ComposePlayer.Callback() {
      override fun onPlayerStateChanged(state: ComposePlayerState) {
        logMsg { "onPlayerStateChanged:$state" }
      }

      override fun onPlayerError(error: ComposePlayerException) {
        logMsg { "onPlayerError:${error.stackTraceToString()}" }
      }
    })
    player.setEventCallback(object : ComposePlayerRtsp.EventCallback() {
      override fun onStuckRenderedFrame() {
        logMsg { "onStuckRenderedFrame" }
      }

      override fun onStuckPosition() {
        logMsg { "onStuckPosition" }
      }
    })
    player.play()
  }

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .background(Color.Gray),
      contentAlignment = Alignment.Center,
    ) {
      ComposePlayerView(
        modifier = Modifier
          .fillMaxSize()
          .graphicsLayer { scaleX = -1f },
        player = player,
      )
      VideoDurationView(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .safeDrawingPadding(),
        player = player,
      )
      videoSize?.let {
        Text(
          modifier = Modifier.align(Alignment.BottomEnd),
          text = "${it.first}x${it.second}",
        )
      }
      exception?.let {
        Text(text = it.desc(context), color = Color.Red)
      }
    }

    Text(text = playerState.name)
    Text(text = bufferState.name)

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

@Composable
private fun VideoDurationView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  var time by remember { mutableStateOf("") }

  LaunchedEffect(player) {
    while (true) {
      time = "${player.getCurrentPosition()}｜${formatDuration(player.getCurrentPosition())}"
      delay(200)
    }
  }

  if (time.isNotEmpty()) {
    Text(
      modifier = modifier,
      text = time,
    )
  }
}