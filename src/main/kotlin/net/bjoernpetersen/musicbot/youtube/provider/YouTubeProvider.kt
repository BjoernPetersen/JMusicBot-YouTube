package net.bjoernpetersen.musicbot.youtube.provider

import com.google.api.services.youtube.YouTube
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.plugin.Provider

@IdBase("YouTube")
interface YouTubeProvider : Provider {

    override val name: String
        get() = "YouTube"
    val api: YouTube
    val apiKey: String
}
