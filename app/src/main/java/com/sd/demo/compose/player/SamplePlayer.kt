package com.sd.demo.compose.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
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
import com.sd.lib.compose.player.seekDelta
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
    player.setLooping(true)
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
          .align(Alignment.TopStart)
          .safeDrawingPadding(),
        text = playerState.name,
      )

      VideoDurationView(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .safeDrawingPadding(),
        player = player,
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
        VideoControlView(player = player)
        VideoSpeedView(player = player)
      }
    }

    VideoProgressView(player = player)
  }
}

/** 视频控制 */
@Composable
private fun VideoControlView(
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
      player.seekDelta(-3000)
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
      player.seekDelta(3000)
    }) {
      Text(text = "⏩")
    }
  }
}

/** 视频倍速 */
@Composable
private fun VideoSpeedView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  val speed by player.speedFlow.collectAsStateWithLifecycle()
  val listSpeed = remember { listOf(0.5f, 1.0f, 1.5f) }
  LazyRow(modifier = modifier) {
    items(listSpeed) { item ->
      TextButton(onClick = {
        player.setSpeed(item)
      }) {
        Text(
          text = item.toString(),
          color = if (item == speed) Color.Green else Color.Unspecified,
        )
      }
    }
  }
}

/** 视频时长 */
@Composable
private fun VideoDurationView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  var time by remember { mutableStateOf("") }

  val duration by player.durationFlow.collectAsStateWithLifecycle()
  LaunchedEffect(player, duration) {
    while (true) {
      time = "${formatDuration(player.getCurrentPosition())}/${formatDuration(duration)}"
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

/** 视频进度 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoProgressView(
  modifier: Modifier = Modifier,
  player: ComposePlayer,
) {
  // 视频总时长
  val durationState = player.durationFlow.collectAsStateWithLifecycle()
  val duration = if (LocalInspectionMode.current) 10000 else durationState.value

  // 如果未获取到时长，不显示控件
  if (duration <= 0) return

  val sliderState = remember(duration) {
    SliderState(
      value = player.getCurrentPosition().toFloat(),
      valueRange = 0f..duration.toFloat(),
    )
  }

  // 设置拖动结束回调
  sliderState.onValueChangeFinished = remember(player) {
    {
      player.seekTo(sliderState.value.toLong())
    }
  }

  val playerState by player.playerStateFlow.collectAsStateWithLifecycle()
  val interactionSource = remember { MutableInteractionSource() }
  val isDragged by interactionSource.collectIsDraggedAsState()

  // 如果没有拖动且正在播放，则更新进度
  if (!isDragged && playerState == ComposePlayerState.Playing) {
    LaunchedEffect(player) {
      while (true) {
        sliderState.value = player.getCurrentPosition().toFloat()
        delay(200)
      }
    }
  }

  Slider(
    modifier = modifier.fillMaxWidth(),
    state = sliderState,
    interactionSource = interactionSource,
  )
}

/** 格式化时长 */
private fun formatDuration(ms: Long): String {
  val totalSeconds = (ms / 1000).coerceAtLeast(0)
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%02d:%02d".format(minutes, seconds)
}

@Preview
@Composable
private fun Preview() {
  AppTheme {
    Content()
  }
}