package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.playback.AbstractPlayback;
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
  private final WebEngine engine;

  YouTubePlayback(@Nonnull WebEngine engine, String videoId) {
    super();
    this.engine = engine;

    engine.executeScript("player.cueVideoById('" + videoId + "');");
    // We're abusing the alert handler to get signals from the JS code.
    engine.setOnAlert(event -> {
      if (PLAYER_STOP_CODE.equals(event.getData())) {
        markDone();
      }
    });
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
    Platform.runLater(() -> engine.executeScript("player.stopVideo();"));
    super.close();
  }
}
