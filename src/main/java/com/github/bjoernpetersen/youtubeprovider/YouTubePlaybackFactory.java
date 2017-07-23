package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.playback.Playback;
import com.github.bjoernpetersen.jmusicbot.playback.PlaybackFactory;
import java.io.IOException;
import javax.annotation.Nonnull;

public interface YouTubePlaybackFactory extends PlaybackFactory {

  @Nonnull
  Playback createPlayback(String id) throws IOException;
}
