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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sd.demo.compose.player.theme.AppTheme
import com.sd.lib.compose.player.ComposePlayer
import com.sd.lib.compose.player.ComposePlayerBufferState
import com.sd.lib.compose.player.ComposePlayerException
import com.sd.lib.compose.player.ComposePlayerState
import com.sd.lib.compose.player.ComposePlayerView
import com.sd.lib.compose.player.ComposePlayerViewSurfaceType
import com.sd.lib.compose.player.rememberComposePlayer

class SamplePlayer : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      AppTheme {
        Content()
      }
    }
  }
}

@Composable
private fun Content(
  modifier: Modifier = Modifier,
) {
  val player = rememberComposePlayer()

  val playerState by player.playerStateFlow.collectAsStateWithLifecycle()
  val bufferState by player.bufferStateFlow.collectAsStateWithLifecycle()
  var errorTips by remember { mutableStateOf("") }

  LaunchedEffect(player) {
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
        logMsg { "onPlayerError:$error" }
        errorTips = error.toString()
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
        modifier = Modifier.fillMaxSize(),
        player = player,
        surfaceType = ComposePlayerViewSurfaceType.TextureView,
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
        player.setDataSource("asset:///demo.mp4")
      }) {
        Text("setDataSource")
      }

      Button(onClick = {
        player.play()
      }) {
        Text("play")
      }

      Button(onClick = {
        player.pause()
      }) {
        Text("pause")
      }

      Button(onClick = {
        player.stop()
      }) {
        Text("stop")
      }

      Button(onClick = {
        player.seekTo(10_000)
      }) {
        Text("seekTo")
      }

      Button(onClick = {
        logMsg { "getCurrentPosition:${player.getCurrentPosition()}" }
      }) {
        Text("getCurrentPosition")
      }
    }
  }
}