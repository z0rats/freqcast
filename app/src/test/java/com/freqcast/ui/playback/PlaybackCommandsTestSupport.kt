package com.freqcast.ui.playback

import android.content.Intent

/** Decodes a started-service [Intent] the way [com.freqcast.ui.RadioPlaybackService] would; null unless it's a start command. */
fun startCommandOf(intent: Intent?): PlaybackCommands.Command.Start? =
    PlaybackCommands.parse(intent) as? PlaybackCommands.Command.Start
