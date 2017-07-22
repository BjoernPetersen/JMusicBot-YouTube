package com.github.bjoernpetersen.youtubeprovider;

import com.github.bjoernpetersen.jmusicbot.provider.Provider;
import com.google.api.services.youtube.YouTube;
import javax.annotation.Nonnull;

public interface YouTubeProviderBase extends Provider {

  @Nonnull
  String getApiKey();

  @Nonnull
  YouTube getYoutube();
}
