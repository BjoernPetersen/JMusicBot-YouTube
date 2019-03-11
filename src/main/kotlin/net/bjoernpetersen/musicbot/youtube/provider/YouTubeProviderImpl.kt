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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.PasswordBox
import net.bjoernpetersen.musicbot.api.player.Song
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.NoSuchSongException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.youtube.playback.YouTubePlaybackFactory
import java.io.IOException
import java.time.Duration
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.min

private const val SEARCH_RESULT_PARTS = "id,snippet"
private const val VIDEO_RESULT_PARTS = "contentDetails"
private const val SEARCH_TYPE = "video"

class YouTubeProviderImpl : YouTubeProvider, CoroutineScope {
    override val description: String
        get() = "Provides YouTube videos/songs"
    override val subject: String
        get() = name

    private val logger = KotlinLogging.logger { }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    private lateinit var apiKeyEntry: Config.StringEntry
    override val apiKey: String
        get() = apiKeyEntry.get()!!

    @Inject
    private lateinit var playback: YouTubePlaybackFactory
    override lateinit var api: YouTube
        private set

    private lateinit var songCache: LoadingCache<String, Deferred<Song>>
    private lateinit var searchCache: LoadingCache<String, Deferred<List<Song>>>

    override fun createStateEntries(state: Config) {}
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> = emptyList()

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> {
        apiKeyEntry = secrets.StringEntry(
            "apiKey",
            "YouTube API key",
            NonnullConfigChecker,
            PasswordBox
        )

        return listOf(apiKeyEntry)
    }

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Creating API access object")
        api = YouTube
            .Builder(
                NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                null
            )
            .setApplicationName("music-bot")
            .build()

        songCache = CacheBuilder.newBuilder()
            .initialCapacity(128)
            .maximumSize(2048)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build(object : CacheLoader<String, Deferred<Song>>() {
                @Throws(Exception::class)
                override fun load(key: String): Deferred<Song> {
                    return runBlocking {
                        async(coroutineContext) {
                            lookupSong(key)
                                ?: throw NoSuchSongException(key, YouTubeProvider::class)
                        }
                    }
                }
            })

        searchCache = CacheBuilder.newBuilder()
            .initialCapacity(128)
            .maximumSize(512)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(object : CacheLoader<String, Deferred<List<Song>>>() {
                @Throws(Exception::class)
                override fun load(key: String): Deferred<List<Song>> {
                    return runBlocking {
                        async(coroutineContext) {
                            actualSearch(key, 0)
                        }
                    }
                }
            })
    }

    private suspend fun createSongs(searchResults: List<SearchResult>): List<Song> {
        val result = Array<Deferred<Song>?>(searchResults.size) { null }
        val toBeLookedUp = ArrayList<Pair<Int, SearchResult>>(searchResults.size)

        searchResults.forEachIndexed { index, searchResult ->
            val cached = songCache.getIfPresent(searchResult.id.videoId)
            if (cached != null) result[index] = cached
            else toBeLookedUp.add(index to searchResult)
        }

        if (toBeLookedUp.isNotEmpty())
            logger.debug { "Looking up search result IDs, size: ${toBeLookedUp.size}" }

        try {
            withContext(coroutineContext) {
                for (partition in Lists.partition(toBeLookedUp, 50)) {
                    val ids = partition.joinToString(",") { pair -> pair.second.id.videoId }

                    val videos: List<Video> = api.videos().list(VIDEO_RESULT_PARTS)
                        .setKey(apiKey)
                        .setId(ids)
                        .execute()
                        .items

                    val resultPairIterator = partition.iterator()
                    val videoIterator = videos.iterator()
                    while (videoIterator.hasNext()) {
                        val resultPair = resultPairIterator.next()
                        val song = createSong(resultPair.second, videoIterator.next())
                        val deferredSong = CompletableDeferred(song)
                        result[resultPair.first] = deferredSong
                        songCache.put(song.id, deferredSong)
                    }
                }
            }
        } catch (e: IOException) {
            logger.error(e) { "IOException during video lookup" }
            return emptyList()
        }

        return withContext(coroutineContext) {
            result.map { it!!.await() }
        }
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
            albumArtUrl = medium?.url
        )
    }

    private fun getDuration(encodedDuration: String): Int {
        return Duration.parse(encodedDuration).seconds.toInt()
    }

    override suspend fun search(query: String, offset: Int): List<Song> {
        val trimmedQuery = query.trim()
        return when {
            trimmedQuery.isEmpty() -> emptyList()
            offset == 0 -> try {
                searchCache.get(trimmedQuery).await()
            } catch (e: Exception) {
                logger.warn(e) { "Error during search for $trimmedQuery" }
                emptyList<Song>()
            }
            else -> actualSearch(trimmedQuery, offset).also {
                searchCache.put(trimmedQuery, CompletableDeferred(it))
            }
        }
    }

    private suspend fun actualSearch(query: String, offset: Int): List<Song> {
        if (query.isBlank()) {
            return emptyList()
        }

        logger.debug { "Actually searching with offset $offset: $query" }

        val searchResults: List<SearchResult> = try {
            withContext(coroutineContext) {
                api.search().list(SEARCH_RESULT_PARTS)
                    .setKey(apiKey)
                    .setQ(query)
                    .setType(SEARCH_TYPE)
                    .setMaxResults(50L)
                    .execute()
                    .items
            }
        } catch (e: IOException) {
            logger.error(e) { "IOException during search" }
            return emptyList()
        }

        val fixedResults = searchResults
            .subList(max(0, min(offset, searchResults.size - 1)), min(searchResults.size, 50))
            .filter { s -> s.id.videoId != null }

        return createSongs(fixedResults)
    }

    override suspend fun lookup(id: String): Song {
        return songCache.get(id).await()
    }

    private suspend fun lookupSong(id: String): Song? {
        logger.debug { "Looking up ID $id" }

        val results: List<SearchResult> = try {
            withContext(coroutineContext) {
                api.search().list(SEARCH_RESULT_PARTS)
                    .setKey(apiKey)
                    .setQ(id)
                    .setType(SEARCH_TYPE)
                    .execute()
                    .items
            }
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

    override suspend fun loadSong(song: Song): Resource {
        return playback.load(song.id)
    }

    override suspend fun supplyPlayback(song: Song, resource: Resource): Playback {
        return playback.createPlayback(song.id, resource)
    }

    override suspend fun close() {
        job.cancel()
    }
}
