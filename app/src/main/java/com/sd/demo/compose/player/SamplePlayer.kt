package com.sd.demo.compose.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sd.demo.compose.player.theme.AppTheme
import com.sd.lib.compose.player.ComposePlayer
import com.sd.lib.compose.player.ComposePlayerBufferState
import com.sd.lib.compose.player.ComposePlayerException
import com.sd.lib.compose.player.ComposePlayerState
import com.sd.lib.compose.player.ComposePlayerView
import com.sd.lib.compose.player.rememberComposePlayer
import kotlinx.coroutines.delay

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
    player.setDataSource("asset:///demo.mp4")
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
        .fillMaxHeight(0.5f)
        .background(Color.Gray),
      contentAlignment = Alignment.Center,
    ) {
      ComposePlayerView(
        modifier = Modifier.fillMaxSize(),
        player = player,
      )

      Text(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .safeDrawingPadding(),
        text = playerState.name,
      )

      if (bufferState == ComposePlayerBufferState.Buffering) {
        CircularProgressIndicator()
      }

      if (errorTips.isNotEmpty()) {
        Text(text = errorTips, color = Color.Red)
      }

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.BottomCenter)
          .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        VideoControlBar(player = player)
        VideoProgressBar(player = player)
      }
    }
  }
}

@Composable
private fun VideoControlBar(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  val playerState by player.playerStateFlow.collectAsStateWithLifecycle()

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // 快退
    TextButton(onClick = {
      val current = player.getCurrentPosition()
      if (current > 0) {
        val target = current - 3000
        player.seekTo(target)
      }
    }) {
      Text(text = "⏪")
    }

    // 播放/暂停
    TextButton(onClick = {
      when (playerState) {
        ComposePlayerState.Playing -> player.pause()
        else -> player.play()
      }
    }) {
      val text = when (playerState) {
        ComposePlayerState.Playing -> "⏸️"
        else -> "▶️"
      }
      Text(text = text)
    }

    // 快进
    TextButton(onClick = {
      val current = player.getCurrentPosition()
      if (current > 0) {
        val target = current + 3000
        player.seekTo(target)
      }
    }) {
      Text(text = "⏩")
    }
  }
}

@Composable
private fun VideoProgressBar(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  var progress by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(player) {
    while (true) {
      val total = player.getDuration()
      val current = player.getCurrentPosition()
      progress = if (total > 0) current / total.toFloat() else 0f
      delay(200)
    }
  }

  Slider(
    modifier = modifier.fillMaxWidth(),
    value = progress,
    onValueChange = { value ->
      val total = player.getDuration()
      if (total > 0) {
        val target = total * value
        player.seekTo(target.toLong())
      }
    },
  )
}

@Preview
@Composable
private fun Preview() {
  AppTheme {
    Content()
  }
}