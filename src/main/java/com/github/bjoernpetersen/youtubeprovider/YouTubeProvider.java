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
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

public class YouTubeProvider implements Loggable, Provider {

  @Nonnull
  private static final String SEARCH_RESULT_PARTS = "id,snippet";
  @Nonnull
  private static final String VIDEO_RESULT_PARTS = "contentDetails";

  private YouTubePlaybackFactory playbackFactory;
  private Config.StringEntry apiKeyEntry;

  private Song.Builder builder;
  private YouTube youtube;
  private String apiKey;

  @Nonnull
  @Override
  public List<? extends Entry> initializeConfigEntries(Config config) {
    apiKeyEntry = config.secret(getClass(), "apiKey", "YouTube API key");
    return Collections.singletonList(apiKeyEntry);
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
    apiKey = apiKeyEntry.get()
        .orElseThrow(() -> new InitializationException("Missing YouTube API key."));
    builder = initializeSongBuilder();
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
  public void destructConfigEntries() {
    apiKeyEntry.tryDestruct();
    apiKeyEntry = null;
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

    return createSongs(searchResults);
  }

  @Nonnull
  private List<Song> createSongs(List<SearchResult> searchResults) {
    List<Song> result = new ArrayList<>(searchResults.size());
    while (!searchResults.isEmpty()) {
      StringBuilder builder = new StringBuilder();
      for (int index = 0; index < 50 && index < searchResults.size(); ++index) {
        builder.append(searchResults.get(index).getId().getVideoId()).append(',');
      }
      builder.deleteCharAt(builder.length() - 1);

      List<Video> videos;
      try {
        videos = youtube.videos().list(VIDEO_RESULT_PARTS)
            .setKey(apiKey)
            .setId(builder.toString())
            .execute().getItems();
      } catch (IOException e) {
        logInfo(e, "IOException during video lookup");
        return Collections.emptyList();
      }

      Iterator<SearchResult> resultIterator = searchResults.iterator();
      Iterator<Video> videoIterator = videos.iterator();
      while (videoIterator.hasNext()) {
        result.add(createSong(resultIterator.next(), videoIterator.next()));
      }

      int size = searchResults.size();
      if (size <= 50) {
        searchResults = Collections.emptyList();
      } else {
        searchResults = searchResults.subList(50, size);
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

  @Nonnull
  @Override
  public Song lookup(@Nonnull String id) throws NoSuchSongException {
    // TODO cache search results / lookups
    List<SearchResult> results;
    try {
      results = youtube.search().list(SEARCH_RESULT_PARTS)
          .setKey(apiKey)
          .setQ(id)
          .setType("video")
          .execute().getItems();
    } catch (IOException e) {
      logSevere(e, "Error looking up song");
      throw new NoSuchSongException(e);
    }

    if (results.isEmpty()) {
      throw new NoSuchSongException("Song with ID '" + id + "' does not exist.");
    }

    SearchResult result = results.get(0);
    if (!result.getId().getVideoId().equals(id)) {
      throw new NoSuchSongException("Song with ID '" + id + "' does not exist.");
    }

    return createSongs(Collections.singletonList(result)).get(0);
  }

  @Nonnull
  @Override
  public String getName() {
    return "youtube";
  }

  @Nonnull
  @Override
  public String getReadableName() {
    return "YouTube";
  }
}
