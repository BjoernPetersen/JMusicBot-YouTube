package com.github.bjoernpetersen.youtubeprovider;

import android.os.Looper;
import android.webkit.WebView;
import com.github.bjoernpetersen.jmusicbot.InitStateWriter;
import com.github.bjoernpetersen.jmusicbot.InitializationException;
import com.github.bjoernpetersen.jmusicbot.Loggable;
import com.github.bjoernpetersen.jmusicbot.config.Config;
import com.github.bjoernpetersen.jmusicbot.config.Config.Entry;
import com.github.bjoernpetersen.jmusicbot.platform.ContextHolder;
import com.github.bjoernpetersen.jmusicbot.platform.Support;
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
import javax.annotation.Nonnull;

public class AndroidYouTubePlaybackFactory implements Loggable, YouTubePlaybackFactory {

  @Nonnull
  private static final String playerHtml = loadHtml("PlayerHtml.html");

  private WebView webView;
  private AndroidChromeClient client;

  @Nonnull
  @Override
  public Support getSupport(
      @Nonnull com.github.bjoernpetersen.jmusicbot.platform.Platform platform) {
    switch (platform) {
      case ANDROID:
        return Support.YES;
      case LINUX:
      case WINDOWS:
      case UNKNOWN:
      default:
        return Support.NO;
    }
  }

  @Nonnull
  @Override
  public String getReadableName() {
    return "YouTube PlaybackFactory";
  }

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
  public void initialize(@Nonnull InitStateWriter initStateWriter)
      throws InitializationException, InterruptedException {
    Lock lock = new ReentrantLock();
    Condition loaded = lock.newCondition();

    Runnable load = () -> {
      webView = new WebView(ContextHolder.INSTANCE.getContext());
      webView.getSettings().setJavaScriptEnabled(true);
      client = new AndroidChromeClient(message -> {
        if ("loaded".equals(message)) {
          lock.lock();
          try {
            loaded.signalAll();
          } finally {
            lock.unlock();
          }
        }
      });

      webView.setWebChromeClient(client);
      webView
          .loadDataWithBaseURL("https://www.youtube.com", playerHtml, "text/html", "utf-8", null);
    };

    lock.lock();
    try {
      initStateWriter.state("Checking if running on Android UI thread");
      if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
        throw new InitializationException(
            "Initialization should not be performed on Android UI thread."
        );
      }

      webView.post(load);

      initStateWriter.state("Waiting for YouTube player to be ready to play (up to 20 seconds)");
      if (!loaded.await(20, TimeUnit.SECONDS)) {
        throw new InitializationException("YouTube player did not load within 20 seconds.");
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void destructConfigEntries() {
  }

  @Override
  public void close() throws IOException {
    webView.loadUrl("about:blank");
    client = null;
    webView = null;
  }

  @Override
  @Nonnull
  public Playback createPlayback(String id) throws IOException {
    return new AndroidYouTubePlayback(webView, client, id);
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