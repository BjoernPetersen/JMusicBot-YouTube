package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.InitializationException;
import com.github.bjoernpetersen.jmusicbot.PlaybackFactoryManager;
import com.github.bjoernpetersen.jmusicbot.PlaybackSupplier;
import com.github.bjoernpetersen.jmusicbot.Song;
import com.github.bjoernpetersen.jmusicbot.SongLoader;
import com.github.bjoernpetersen.jmusicbot.config.Config;
import com.github.bjoernpetersen.jmusicbot.config.Config.Entry;
import com.github.bjoernpetersen.jmusicbot.playback.Playback;
import com.github.bjoernpetersen.jmusicbot.provider.NoSuchSongException;
import com.github.bjoernpetersen.jmusicbot.provider.Provider;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.SearchResultSnippet;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

public class YouTubeProvider implements Provider {

  @Nonnull
  private static final String SEARCH_RESULT_PARTS = "id,snippet";

  @Nonnull
  private static final Logger log = Logger.getLogger(YouTubeProvider.class.getName());

  private PlaybackSupplier playbackSupplier;
  private SongLoader songLoader;

  private YouTubePlaybackFactory playbackFactory;
  private Config.StringEntry apiKeyEntry;

  private YouTube youtube;
  private String apiKey;

  public YouTubeProvider() {
    playbackSupplier = new PlaybackSupplier() {
      @Nonnull
      @Override
      public Playback supply(Song song) throws IOException {
        return playbackFactory.createPlayback(song.getId());
      }
    };
    songLoader = SongLoader.DUMMY;
  }

  @Nonnull
  @Override
  public List<? extends Entry> initializeConfigEntries(Config config) {
    apiKeyEntry = config.secret(getClass(), "apiKey", "YouTube API key");
    return Collections.singletonList(apiKeyEntry);
  }

  @Override
  public void initialize(@Nonnull PlaybackFactoryManager manager) throws InitializationException {
    playbackFactory = manager.getFactory(YouTubePlaybackFactory.class);
    youtube = new YouTube.Builder(
        new NetHttpTransport(),
        JacksonFactory.getDefaultInstance(),
        httpRequest -> {
        }
    ).setApplicationName("music-bot").build();
    apiKey = apiKeyEntry.get()
        .orElseThrow(() -> new InitializationException("Missing YouTube API key."));
  }

  @Override
  public void destructConfigEntries() {
    apiKeyEntry.tryDestruct();
    apiKeyEntry = null;
  }

  @Override
  public void close() throws IOException {
  }

  @Nonnull
  private Song createSong(@Nonnull String id, @Nonnull String title, @Nonnull String description) {
    return new Song(
        playbackSupplier,
        songLoader,
        getName(),
        id,
        title,
        description
    );
  }

  @Nonnull
  @Override
  public List<Song> search(@Nonnull String query) {
    try {
      return youtube.search().list(SEARCH_RESULT_PARTS)
          .setKey(apiKey)
          .setQ(query)
          .execute().getItems().stream()
          .map(this::getSongFromSearchResult)
          .collect(Collectors.toList());
    } catch (IOException e) {
      log.severe("IOException during search: " + e);
      return Collections.emptyList();
    }
  }

  @Nonnull
  private Song getSongFromSearchResult(SearchResult result) {
    SearchResultSnippet snippet = result.getSnippet();
    return createSong(result.getId().getVideoId(), snippet.getTitle(), snippet.getDescription());
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
          .execute().getItems();
    } catch (IOException e) {
      log.severe("Error looking up song: " + e);
      throw new NoSuchSongException(e);
    }

    if (results.isEmpty()) {
      throw new NoSuchSongException("Song with ID '" + id + "' does not exist.");
    }

    SearchResult result = results.get(0);
    if (!result.getId().getVideoId().equals(id)) {
      throw new NoSuchSongException("Song with ID '" + id + "' does not exist.");
    }

    return getSongFromSearchResult(result);
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
