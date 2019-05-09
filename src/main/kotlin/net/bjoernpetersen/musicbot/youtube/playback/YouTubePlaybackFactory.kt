package net.bjoernpetersen.musicbot.youtube.playback

import net.bjoernpetersen.musicbot.api.plugin.Base
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.PlaybackFactory

@Base
interface YouTubePlaybackFactory : PlaybackFactory {

    suspend fun load(videoId: String): YouTubeResource
    @Deprecated("Use overloaded method with YouTubeReference instead")
    suspend fun createPlayback(videoId: String, resource: Resource): Playback

    suspend fun createPlayback(resource: YouTubeResource): Playback
}

abstract class YouTubeResource(val videoId: String) : Resource
