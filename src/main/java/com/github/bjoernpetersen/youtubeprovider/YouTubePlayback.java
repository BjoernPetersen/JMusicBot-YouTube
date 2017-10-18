package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.Loggable;
import com.github.bjoernpetersen.jmusicbot.playback.AbstractPlayback;
import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;
import javax.annotation.Nonnull;

public class YouTubePlayback extends AbstractPlayback implements Loggable {

  @Nonnull
  private static final String PLAYER_STOP_CODE = "0";

  @Nonnull
  private final String videoId;
  @Nonnull
  private final WebEngine engine;

  YouTubePlayback(@Nonnull WebEngine engine, @Nonnull String quality, @Nonnull String videoId)
      throws IOException {
    super();
    this.videoId = videoId;
    this.engine = engine;

    Runnable prepare = () -> {
      logFiner("CueVideo with ID " + videoId);
      engine.executeScript("player.cueVideoById('" + videoId + "', 0, '" + quality + "');");
      // We're abusing the alert handler to get signals from the JS code.
      engine.setOnAlert(event -> {
        if (PLAYER_STOP_CODE.equals(event.getData())) {
          logFiner("Received stop signal from player");
          markDone();
        } else {
          logFinest("Received unknown signal from player: " + event.getData());
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

    logFinest("Playback init done for ID " + videoId);
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
    logFiner("Closing playback for song " + videoId);
    Platform.runLater(() -> {
      engine.executeScript("player.stopVideo();");
      engine.setOnAlert(null);
    });
    super.close();
    logFinest("Closed playback for song " + videoId);
  }
}
