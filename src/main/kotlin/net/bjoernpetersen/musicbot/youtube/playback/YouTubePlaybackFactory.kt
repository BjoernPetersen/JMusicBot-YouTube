package net.bjoernpetersen.musicbot.youtube.playback

import net.bjoernpetersen.musicbot.api.plugin.Base
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.PlaybackFactory

@Base
interface YouTubePlaybackFactory : PlaybackFactory {

    suspend fun load(videoId: String): YouTubeResource
    suspend fun createPlayback(resource: YouTubeResource): Playback
}

abstract class YouTubeResource(val videoId: String) : Resource
