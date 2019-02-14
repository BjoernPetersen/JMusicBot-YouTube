package net.bjoernpetersen.musicbot.youtube.provider

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.SearchResult
import com.google.api.services.youtube.model.Video
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.collect.Lists
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.PasswordBox
import net.bjoernpetersen.musicbot.api.config.TextBox
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.youtube.playback.YouTubePlaybackFactory
import java.io.IOException
import java.time.Duration
import java.util.ArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val SEARCH_RESULT_PARTS = "id,snippet"
private const val VIDEO_RESULT_PARTS = "contentDetails"
private const val SEARCH_TYPE = "video"

class YouTubeProviderImpl : YouTubeProvider {
    override val description: String
        get() = "Provides YouTube videos/songs"
    override val subject: String
        get() = name

    private val logger = KotlinLogging.logger { }

    private lateinit var applicationNameEntry: Config.StringEntry

    private lateinit var apiKeyEntry: Config.StringEntry
    override val apiKey: String
        get() = apiKeyEntry.get()!!

    @Inject
    private lateinit var playback: YouTubePlaybackFactory
    override lateinit var api: YouTube
        private set

    private lateinit var cache: LoadingCache<String, Song>

    override fun createStateEntries(state: Config) {}
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        applicationNameEntry = config.StringEntry(
            "applicationName",
            "OAuth application name",
            NonnullConfigChecker,
            TextBox)

        return listOf(applicationNameEntry)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        apiKeyEntry = secrets.StringEntry(
            "apiKey",
            "YouTube API key",
            NonnullConfigChecker,
            PasswordBox)

        return listOf(apiKeyEntry)
    }

    override fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Creating API access object")
        api = YouTube
            .Builder(
                NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                null)
            .setApplicationName("music-bot")
            .build()

        cache = CacheBuilder.newBuilder()
            .initialCapacity(128)
            .maximumSize(1024)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build(object : CacheLoader<String, Song>() {
                @Throws(Exception::class)
                override fun load(key: String): Song {
                    return lookupSong(key) ?: throw NoSuchSongException(key, YouTubeProvider::class)
                }
            })
    }

    override fun search(query: String, offset: Int): List<Song> {
        if (query.isBlank()) {
            return emptyList()
        }

        val searchResults: List<SearchResult> = try {
            api.search().list(SEARCH_RESULT_PARTS)
                .setKey(apiKey)
                .setQ(query)
                .setType(SEARCH_TYPE)
                .setMaxResults(50L)
                .execute()
                .items
        } catch (e: IOException) {
            logger.error(e) { "IOException during search" }
            return emptyList()
        }

        val fixedResults = searchResults
            .subList(0, Math.min(searchResults.size, 50))
            .filter { s -> s.id.videoId != null }

        return createSongs(fixedResults)
    }

    private fun createSongs(searchResults: List<SearchResult>): List<Song> {
        val result = ArrayList<Song>(searchResults.size)
        for (partition in Lists.partition(searchResults, 50)) {
            val ids = partition.joinToString(",") { r -> r.id.videoId }

            val videos: List<Video> = try {
                api.videos().list(VIDEO_RESULT_PARTS)
                    .setKey(apiKey)
                    .setId(ids)
                    .execute()
                    .items
            } catch (e: IOException) {
                logger.error(e) { "IOException during video lookup" }
                return emptyList()
            }

            val resultIterator = partition.iterator()
            val videoIterator = videos.iterator()
            while (videoIterator.hasNext()) {
                val song = createSong(resultIterator.next(), videoIterator.next())
                result.add(song)
                cache.put(song.id, song)
            }
        }

        return result
    }

    private fun createSong(searchResult: SearchResult, video: Video): Song {
        val snippet = searchResult.snippet
        val medium = snippet.thumbnails.medium
        return Song(
            provider = this,
            id = searchResult.id.videoId,
            title = snippet.title,
            description = snippet.description,
            duration = getDuration(video.contentDetails.duration),
            albumArtUrl = medium?.url)
    }

    private fun getDuration(encodedDuration: String): Int {
        return Duration.parse(encodedDuration).seconds.toInt()
    }

    override fun lookup(id: String): Song {
        try {
            return cache.get(id) ?: throw NoSuchSongException(id, YouTubeProvider::class)
        } catch (e: ExecutionException) {
            throw NoSuchSongException(id, YouTubeProvider::class, e.cause!!)
        }
    }

    private fun lookupSong(id: String): Song? {
        val results: List<SearchResult> = try {
            api.search().list(SEARCH_RESULT_PARTS)
                .setKey(apiKey)
                .setQ(id)
                .setType(SEARCH_TYPE)
                .execute()
                .items
        } catch (e: IOException) {
            logger.error(e) { "Error looking up song" }
            return null
        }

        if (results.isEmpty()) {
            return null
        }

        val result = results[0]
        return if (result.id.videoId != id) null
        else createSongs(listOf(result))[0]
    }

    override fun loadSong(song: Song): Resource {
        return playback.load(song.id)
    }

    override fun supplyPlayback(song: Song, resource: Resource): Playback {
        return playback.getPlayback(song.id, resource)
    }

    override fun close() {}
}
