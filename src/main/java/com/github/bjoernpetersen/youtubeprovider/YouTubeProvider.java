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
import com.github.bjoernpetersen.jmusicbot.provider.NoSuchSongException;
import com.github.bjoernpetersen.jmusicbot.provider.Provider;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.SearchResultSnippet;
import com.google.api.services.youtube.model.Thumbnail;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YouTubeProvider implements Loggable, Provider {

  @Nonnull
  private static final String SEARCH_RESULT_PARTS = "id,snippet";

  private Song.Builder songBuilder;

  private YouTubePlaybackFactory playbackFactory;
  private Config.StringEntry apiKeyEntry;

  private YouTube youtube;
  private String apiKey;

  @Nonnull
  @Override
  public List<? extends Entry> initializeConfigEntries(Config config) {
    apiKeyEntry = config.secret(getClass(), "apiKey", "YouTube API key");
    return Collections.singletonList(apiKeyEntry);
  }

  @Override
  public void initialize(@Nonnull InitStateWriter initStateWriter,
      @Nonnull PlaybackFactoryManager manager) throws InitializationException {
    playbackFactory = manager.getFactory(YouTubePlaybackFactory.class);
    youtube = new YouTube.Builder(
        new NetHttpTransport(),
        JacksonFactory.getDefaultInstance(),
        httpRequest -> {
        }
    ).setApplicationName("music-bot").build();
    apiKey = apiKeyEntry.get()
        .orElseThrow(() -> new InitializationException("Missing YouTube API key."));
    songBuilder = initializeSongBuilder();
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
    songBuilder = null;
  }

  @Nonnull
  private Song createSong(@Nonnull String id, @Nonnull String title, @Nonnull String description,
      @Nullable String albumArtUrl) {
    return songBuilder
        .id(id)
        .title(title)
        .description(description)
        .albumArtUrl(albumArtUrl)
        .build();
  }

  @Nonnull
  @Override
  public List<Song> search(@Nonnull String query) {
    try {
      return youtube.search().list(SEARCH_RESULT_PARTS)
          .setKey(apiKey)
          .setQ(query)
          .setType("video")
          .execute().getItems().stream()
          .map(this::getSongFromSearchResult)
          .collect(Collectors.toList());
    } catch (IOException e) {
      logSevere(e, "IOException during search");
      return Collections.emptyList();
    }
  }

  @Nonnull
  private Song getSongFromSearchResult(SearchResult result) {
    SearchResultSnippet snippet = result.getSnippet();
    Thumbnail medium = snippet.getThumbnails().getMedium();
    return createSong(
        result.getId().getVideoId(),
        snippet.getTitle(),
        snippet.getDescription(),
        medium == null ? null : medium.getUrl()
    );
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
