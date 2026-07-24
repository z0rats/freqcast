package com.freqcast.util

/**
 * User-Agent sent both when scraping a station's homepage for its stream URL
 * ([com.freqcast.data.StationUrlResolver]) and on every subsequent request to that stream URL -
 * by [StreamValidator] when checking reachability, and by playback
 * ([com.freqcast.ui.playback.StreamRecorder], [com.freqcast.ui.RadioPlaybackService]'s HLS data
 * source) when actually playing it. Kept identical everywhere: some Icecast/Shoutcast/AzuraCast
 * mounts allow- or block-list by User-Agent, so validating a stream under a different one than
 * playback ends up using would make the validation unreliable.
 */
const val STREAM_USER_AGENT = "Mozilla/5.0 (compatible; Freqcast/2.0)"
