package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.playback.AbstractPlayback;
import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javax.annotation.Nonnull;

public class YouTubePlayback extends AbstractPlayback {

  @Nonnull
  private static final String PLAYER_STOP_CODE = "0";

  @Nonnull
  private static final Logger log = Logger.getLogger(YouTubePlayback.class.getName());

  @Nonnull
  private final String videoId;
  @Nonnull
  private final WebEngine engine;

  YouTubePlayback(@Nonnull WebEngine engine, @Nonnull String videoId) throws IOException {
    super();
    this.videoId = videoId;
    this.engine = engine;

    Runnable prepare = () -> {
      log.finer("CueVideo with ID " + videoId);
      engine.executeScript("player.cueVideoById('" + videoId + "');");
      // We're abusing the alert handler to get signals from the JS code.
      engine.setOnAlert(event -> {
        if (PLAYER_STOP_CODE.equals(event.getData())) {
          log.finer("Received stop signal from player");
          markDone();
        } else {
          log.finest("Received unknown signal from player: " + event.getData());
        }
      });
    };

    if (Platform.isFxApplicationThread()) {
      prepare.run();
    } else {
      Lock lock = getLock();
      Condition prepared = lock.newCondition();
      Platform.runLater(() -> {
        prepare.run();
        lock.lock();
        try {
          prepared.signalAll();
        } finally {
          lock.unlock();
        }
      });
      lock.lock();
      try {
        prepared.await();
      } catch (InterruptedException e) {
        throw new IOException(e);
      } finally {
        lock.unlock();
      }
    }

    log.finest("Playback init done for ID " + videoId);
  }

  @Override
  public void play() {
    Platform.runLater(() -> engine.executeScript("player.playVideo();"));
  }

  @Override
  public void pause() {
    Platform.runLater(() -> engine.executeScript("player.pauseVideo();"));
  }

  @Override
  public void close() throws Exception {
    log.finer("Closing playback for song " + videoId);
    Platform.runLater(() -> {
      engine.executeScript("player.stopVideo();");
      engine.setOnAlert(null);
    });
    super.close();
    log.finest("Closed playback for song " + videoId);
  }
}
