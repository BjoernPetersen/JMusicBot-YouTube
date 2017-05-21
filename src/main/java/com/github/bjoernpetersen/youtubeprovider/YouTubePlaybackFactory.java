package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.InitializationException;
import com.github.bjoernpetersen.jmusicbot.config.Config;
import com.github.bjoernpetersen.jmusicbot.config.Config.Entry;
import com.github.bjoernpetersen.jmusicbot.playback.Playback;
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javax.annotation.Nonnull;

public class YouTubePlaybackFactory implements PlaybackFactory {

  @Nonnull
  private static final Logger log = Logger.getLogger(YouTubePlaybackFactory.class.getName());
  @Nonnull
  private static final String playerHtml = loadHtml("PlayerHtml.html");

  private WebEngine engine;

  @Nonnull
  @Override
  public List<? extends Entry> initializeConfigEntries(@Nonnull Config config) {
    return Collections.emptyList();
  }

  @Nonnull
  @Override
  public Collection<Class<? extends PlaybackFactory>> getBases() {
    return Collections.singleton(YouTubePlaybackFactory.class);
  }

  @Override
  public void initialize() throws InitializationException {
    Lock lock = new ReentrantLock();
    Condition loaded = lock.newCondition();

    Runnable load = () -> {
      engine = new WebEngine();
      engine.setOnAlert(event -> {
        if (event.getData().equals("loaded")) {
          lock.lock();
          try {
            loaded.signalAll();
          } finally {
            lock.unlock();
          }
        }
      });
      engine.loadContent(playerHtml);
    };

    lock.lock();
    try {
      if (Platform.isFxApplicationThread()) {
        throw new InitializationException("Initialization should not be performed on JFX Thread.");
      }

      Platform.runLater(load);
      if (!loaded.await(20, TimeUnit.SECONDS)) {
        throw new InitializationException("YouTube Player did not load within 20 seconds.");
      }
    } catch (InterruptedException e) {
      log.severe("Interrupted while waiting for YouTube player!");
      throw new InitializationException(e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void destructConfigEntries() {
  }

  @Override
  public void close() throws IOException {
    engine.load("about:blank");
    engine = null;
  }

  @Nonnull
  public Playback createPlayback(String id) throws IOException {
    return new YouTubePlayback(engine, id);
  }

  @Nonnull
  private static String loadHtml(@Nonnull String fileName) {
    try (InputStream in = YouTubePlayback.class.getResourceAsStream(fileName);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "utf-8"))) {
      StringBuilder builder = new StringBuilder(512);
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        builder.append(line).append('\n');
      }
      builder.delete(builder.length() - 1, builder.length());
      return builder.toString();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
