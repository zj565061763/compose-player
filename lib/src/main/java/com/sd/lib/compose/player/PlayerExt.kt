package com.sd.lib.compose.player

import androidx.media3.common.Player

internal fun Player.extSetMute(mute: Boolean) {
  trackSelectionParameters = trackSelectionParameters
    .buildUpon()
    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, mute)
    .build()
}