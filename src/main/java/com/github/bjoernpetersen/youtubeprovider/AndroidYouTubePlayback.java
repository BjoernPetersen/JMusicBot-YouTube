package com.github.bjoernpetersen.youtubeprovider;

import android.os.Looper;
import android.webkit.WebView;
import com.github.bjoernpetersen.jmusicbot.Loggable;
import com.github.bjoernpetersen.jmusicbot.playback.AbstractPlayback;
import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import javax.annotation.Nonnull;

public class AndroidYouTubePlayback extends AbstractPlayback implements Loggable {

  @Nonnull
  private static final String PLAYER_STOP_CODE = "0";

  @Nonnull
  private final String videoId;
  @Nonnull
  private final WebView webView;
  @Nonnull
  private final AndroidChromeClient client;

  AndroidYouTubePlayback(@Nonnull WebView webView, @Nonnull AndroidChromeClient client,
      @Nonnull String videoId) throws IOException {
    super();
    this.videoId = videoId;
    this.webView = webView;
    this.client = client;

    Runnable prepare = () -> {
      logFiner("CueVideo with ID " + videoId);

      webView.evaluateJavascript("player.cueVideoById('" + videoId + "');", null);
      // We're abusing the alert handler to get signals from the JS code.
      client.setOnAlert(message -> {
        if (PLAYER_STOP_CODE.equals(message)) {
          logFiner("Received stop signal from player");
          markDone();
        } else {
          logFinest("Received unknown signal from player: " + message);
        }
      });
    };

    if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
      prepare.run();
    } else {
      Lock lock = getLock();
      Condition prepared = lock.newCondition();
      webView.post(() -> {
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
    webView.post(() -> webView.evaluateJavascript("player.playVideo();", null));
  }

  @Override
  public void pause() {
    webView.post(() -> webView.evaluateJavascript("player.pauseVideo();", null));
  }

  @Override
  public void close() throws Exception {
    logFiner("Closing playback for song " + videoId);
    webView.post(() -> {
      webView.evaluateJavascript("player.stopVideo();", null);
      client.setOnAlert(null);
    });
    super.close();
    logFinest("Closed playback for song " + videoId);
  }
}
