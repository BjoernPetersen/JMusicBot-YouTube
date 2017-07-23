package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.platform.Platform;
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactory;
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactoryLoader;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class YouTubePlaybackFactoryLoader implements PlaybackFactoryLoader {

  @Nonnull
  @Override
  public String getReadableName() {
    return "YouTube PlaybackFactory";
  }

  @Nullable
  @Override
  public PlaybackFactory load(@Nonnull Platform platform) {
    switch (platform) {
      case LINUX:
      case WINDOWS:
      case UNKNOWN:
        return new DesktopYouTubePlaybackFactory();
      case ANDROID:
        // TODO support android
      default:
        return null;
    }
  }
}
