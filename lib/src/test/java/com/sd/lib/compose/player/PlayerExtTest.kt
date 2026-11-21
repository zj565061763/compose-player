package com.sd.lib.compose.player

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerExtTest {
  @Test
  fun createsDefaultMediaAudioAttributes() {
    val attributes = newMediaAudioAttributes()
    assertEquals(C.USAGE_MEDIA, attributes.usage)
    assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, attributes.contentType)
  }

  @Test
  fun updatesVolumeSpeedAndLoopingIdempotently() {
    val controller = FakeExoPlayerController()
    val player = controller.exoPlayer

    player.extSetMute(false)
    assertEquals(0, controller.volumeSetCount)

    player.extSetMute(true)
    assertEquals(0f, controller.volume)
    assertEquals(1, controller.volumeSetCount)

    player.extSetMute(true)
    assertEquals(1, controller.volumeSetCount)

    player.extSetMute(false)
    assertEquals(1f, controller.volume)
    assertEquals(2, controller.volumeSetCount)

    player.extSetSpeed(1.0f)
    assertEquals(0, controller.playbackParametersSetCount)

    player.extSetSpeed(1.5f)
    assertEquals(1.5f, controller.playbackParameters.speed)
    assertEquals(1, controller.playbackParametersSetCount)

    player.extSetSpeed(1.5f)
    assertEquals(1, controller.playbackParametersSetCount)

    player.extSetLooping(false)
    assertEquals(0, controller.repeatModeSetCount)

    player.extSetLooping(true)
    assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
    assertEquals(1, controller.repeatModeSetCount)

    player.extSetLooping(true)
    assertEquals(1, controller.repeatModeSetCount)

    player.extSetLooping(false)
    assertEquals(Player.REPEAT_MODE_OFF, controller.repeatMode)
    assertEquals(2, controller.repeatModeSetCount)
  }
}
