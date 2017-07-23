package com.github.bjoernpetersen.youtubeprovider;

import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class AndroidChromeClient extends WebChromeClient {

  @Nonnull
  private OnAlertListener listener = m -> {
  };

  public AndroidChromeClient() {
  }

  public AndroidChromeClient(@Nonnull OnAlertListener listener) {
    this.listener = listener;
  }

  void setOnAlert(@Nullable OnAlertListener listener) {
    this.listener = listener == null ? m -> { } : listener;
  }

  @Override
  public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
    listener.onAlert(message);
    return true;
  }

  @FunctionalInterface
  interface OnAlertListener {

    void onAlert(@Nullable String message);
  }
}
