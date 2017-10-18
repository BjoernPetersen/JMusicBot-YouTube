package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.InitStateWriter;
import com.github.bjoernpetersen.jmusicbot.InitializationException;
import com.github.bjoernpetersen.jmusicbot.Loggable;
import com.github.bjoernpetersen.jmusicbot.PlaybackFactoryManager;
import com.github.bjoernpetersen.jmusicbot.PlaybackSupplier;
import com.github.bjoernpetersen.jmusicbot.Song;
import com.github.bjoernpetersen.jmusicbot.SongLoader;
import com.github.bjoernpetersen.jmusicbot.config.Config;
import com.github.bjoernpetersen.jmusicbot.config.Config.Entry;
import com.github.bjoernpetersen.jmusicbot.platform.Platform;
import com.github.bjoernpetersen.jmusicbot.platform.Support;
import com.github.bjoernpetersen.jmusicbot.playback.Playback;
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactory;
import com.github.bjoernpetersen.jmusicbot.provider.NoSuchSongException;
import com.github.bjoernpetersen.jmusicbot.provider.Provider;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.SearchResultSnippet;
import com.google.api.services.youtube.model.Thumbnail;
import com.google.api.services.youtube.model.Video;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YouTubeProvider implements Loggable, YouTubeProviderBase {

  @Nonnull
  private static final String SEARCH_RESULT_PARTS = "id,snippet";
  @Nonnull
  private static final String VIDEO_RESULT_PARTS = "contentDetails";

  private YouTubePlaybackFactory playbackFactory;
  private Config.StringEntry apiKeyEntry;

  private Song.Builder builder;
  private YouTube youtube;
  private String apiKey;

  private LoadingCache<String, Song> cache;

  @Nonnull
  @Override
  public Class<? extends Provider> getBaseClass() {
    return YouTubeProviderBase.class;
  }

  @Nonnull
  @Override
  public String getApiKey() {
    return apiKey;
  }

  @Nonnull
  @Override
  public YouTube getYoutube() {
    return youtube;
  }

  @Nonnull
  @Override
  public Support getSupport(@Nonnull Platform platform) {
    switch (platform) {
      case LINUX:
      case WINDOWS:
      case ANDROID:
        return Support.YES;
      case UNKNOWN:
      default:
        return Support.MAYBE;
    }
  }

  @Nonnull
  @Override
  public List<? extends Entry> initializeConfigEntries(Config config) {
    apiKeyEntry = config.new StringEntry(getClass(), "apiKey", "YouTube API key", true);
    return Collections.singletonList(apiKeyEntry);
  }

  @Nonnull
  @Override
  public List<? extends Entry> getMissingConfigEntries() {
    if (apiKeyEntry.getValue() == null) {
      return Collections.singletonList(apiKeyEntry);
    }
    return Collections.emptyList();
  }

  @Override
  public void destructConfigEntries() {
    apiKeyEntry.destruct();
    apiKeyEntry = null;
  }

  @Override
  public Set<Class<? extends PlaybackFactory>> getPlaybackDependencies() {
    return Collections.singleton(YouTubePlaybackFactory.class);
  }

  @Override
  public void initialize(@Nonnull InitStateWriter initStateWriter,
      @Nonnull PlaybackFactoryManager manager) throws InitializationException {
    initStateWriter.state("Retrieving PlaybackFactory");
    playbackFactory = manager.getFactory(YouTubePlaybackFactory.class);
    initStateWriter.state("Creating API access object");
    youtube = new YouTube.Builder(
        new NetHttpTransport(),
        JacksonFactory.getDefaultInstance(),
        httpRequest -> {
        }
    ).setApplicationName("music-bot").build();
    initStateWriter.state("Reading API key");
    apiKey = apiKeyEntry.getValue();
    if (apiKey == null) {
      throw new InitializationException("Missing YouTube API key.");
    }
    builder = initializeSongBuilder();
    cache = CacheBuilder.newBuilder()
        .initialCapacity(128)
        .maximumSize(1024)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build(new CacheLoader<String, Song>() {
          @Override
          public Song load(@Nonnull String key) throws Exception {
            return lookupSong(key);
          }
        });
  }

  private Song.Builder initializeSongBuilder() {
    return new Song.Builder()
        .playbackSupplier(new PlaybackSupplier() {
          @Nonnull
          @Override
          public Playback supply(Song song) throws IOException {
            return playbackFactory.createPlayback(song.getId());
          }
        })
        .songLoader(SongLoader.DUMMY)
        .provider(this);
  }

  @Override
  public void close() throws IOException {
    playbackFactory = null;
    youtube = null;
    apiKey = null;
    builder = null;
  }

  @Nonnull
  @Override
  public List<Song> search(@Nonnull String query) {
    if (query.trim().isEmpty()) {
      return Collections.emptyList();
    }
    logFiner("Searching for songs. Query: " + query);
    List<SearchResult> searchResults;
    try {
      searchResults = youtube.search().list(SEARCH_RESULT_PARTS)
          .setKey(apiKey)
          .setQ(query)
          .setType("video")
          .setMaxResults(50L)
          .execute().getItems();
    } catch (IOException e) {
      logInfo(e, "IOException during search");
      return Collections.emptyList();
    }

    searchResults = searchResults.subList(0, Math.min(searchResults.size(), 50)).stream()
        .filter(s -> s.getId().getVideoId() != null)
        .collect(Collectors.toList());

    return createSongs(searchResults);
  }

  @Nonnull
  private List<Song> createSongs(List<SearchResult> searchResults) {
    List<Song> result = new ArrayList<>(searchResults.size());
    for (List<SearchResult> partition : Lists.partition(searchResults, 50)) {
      String ids = partition.stream()
          .map(r -> r.getId().getVideoId())
          .collect(Collectors.joining(","));

      List<Video> videos;
      try {
        videos = youtube.videos().list(VIDEO_RESULT_PARTS)
            .setKey(apiKey)
            .setId(ids)
            .execute().getItems();
      } catch (IOException e) {
        logInfo(e, "IOException during video lookup");
        return Collections.emptyList();
      }

      Iterator<SearchResult> resultIterator = partition.iterator();
      Iterator<Video> videoIterator = videos.iterator();
      while (videoIterator.hasNext()) {
        Song song = createSong(resultIterator.next(), videoIterator.next());
        result.add(song);
        cache.put(song.getId(), song);
      }
    }

    return result;
  }

  @Nonnull
  private Song createSong(SearchResult searchResult, Video video) {
    SearchResultSnippet snippet = searchResult.getSnippet();
    Thumbnail medium = snippet.getThumbnails().getMedium();
    return builder
        .id(searchResult.getId().getVideoId())
        .title(snippet.getTitle())
        .description(snippet.getDescription())
        .duration(getDuration(video.getContentDetails().getDuration()))
        .albumArtUrl(medium == null ? null : medium.getUrl())
        .build();
  }

  private int getDuration(String encodedDuration) {
    return (int) Duration.parse(encodedDuration).getSeconds();
  }

  @Nullable
  private Song lookupSong(@Nonnull String id) {
    List<SearchResult> results;
    try {
      results = youtube.search().list(SEARCH_RESULT_PARTS)
          .setKey(apiKey)
          .setQ(id)
          .setType("video")
          .execute().getItems();
    } catch (IOException e) {
      logInfo(e, "Error looking up song");
      return null;
    }

    if (results.isEmpty()) {
      return null;
    }

    SearchResult result = results.get(0);
    if (!result.getId().getVideoId().equals(id)) {
      return null;
    }

    return createSongs(Collections.singletonList(result)).get(0);
  }

  @Nonnull
  @Override
  public Song lookup(@Nonnull String id) throws NoSuchSongException {
    try {
      Song result = cache.get(id);
      if (result == null) {
        throw new NoSuchSongException("Song with ID '" + id + "' does not exist.");
      }
      return result;
    } catch (ExecutionException e) {
      throw new NoSuchSongException(e.getCause());
    }
  }

  @Nonnull
  @Override
  public String getId() {
    return "youtube";
  }

  @Nonnull
  @Override
  public String getReadableName() {
    return "YouTube";
  }
}
