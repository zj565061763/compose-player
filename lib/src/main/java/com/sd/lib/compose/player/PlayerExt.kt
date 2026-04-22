package com.sd.lib.compose.player

import androidx.media3.common.Player

/** 设置静音 */
internal fun Player.extSetMute(mute: Boolean) {
  trackSelectionParameters = trackSelectionParameters
    .buildUpon()
    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, mute)
    .build()
}

/** 设置倍速 */
internal fun Player.extSetSpeed(speed: Float) {
  if (playbackParameters.speed != speed) {
    playbackParameters = playbackParameters.withSpeed(speed)
  }
}