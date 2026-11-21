package com.sd.lib.compose.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player

/** 创建媒体播放默认音频属性 */
internal fun newMediaAudioAttributes(): AudioAttributes {
  return AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()
}

/** 设置静音 */
internal fun Player.extSetMute(mute: Boolean) {
  val targetVolume = if (mute) 0f else 1f
  if (volume != targetVolume) {
    volume = targetVolume
  }
}

/** 设置倍速 */
internal fun Player.extSetSpeed(speed: Float) {
  if (playbackParameters.speed != speed) {
    playbackParameters = playbackParameters.withSpeed(speed)
  }
}

/** 设置单曲循环模式 */
internal fun Player.extSetLooping(looping: Boolean) {
  val targetMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
  if (repeatMode != targetMode) {
    repeatMode = targetMode
  }
}
