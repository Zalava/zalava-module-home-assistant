package org.zalava.modules.homeassistant;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Validated scoped configuration for one Home Assistant provider instance. */
record HomeAssistantConfiguration(
    URI baseUrl,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBytes) {

  private static final int DEFAULT_TIMEOUT_SECONDS = 10;
  private static final int DEFAULT_MAX_RESPONSE_BYTES = 1_000_000;

  /** True when the non-secret values required to build a provider are present. */
  static boolean isConfigured(Map<String, Object> configuration) {
    return text(configuration, "baseUrl") != null && text(configuration, "tokenRef") != null;
  }

  static HomeAssistantConfiguration from(Map<String, Object> configuration) {
    URI baseUrl = baseUrl(configuration);
    int timeoutSeconds = positiveInt(configuration, "requestTimeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
    int maxResponseBytes =
        positiveInt(configuration, "maxResponseBytes", DEFAULT_MAX_RESPONSE_BYTES);
    return new HomeAssistantConfiguration(
        baseUrl,
        Duration.ofSeconds(timeoutSeconds),
        Duration.ofSeconds(timeoutSeconds),
        maxResponseBytes);
  }

  static String requiredText(Map<String, Object> configuration, String key) {
    String value = text(configuration, key);
    if (value == null) {
      throw new IllegalArgumentException("Home Assistant configuration requires " + key);
    }
    return value;
  }

  private static URI baseUrl(Map<String, Object> configuration) {
    String value = requiredText(configuration, "baseUrl");
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("baseUrl must be a valid URI", exception);
    }
    if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
      throw new IllegalArgumentException("baseUrl must use http or https");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("baseUrl must include a host");
    }
    return uri;
  }

  private static int positiveInt(Map<String, Object> configuration, String key, int fallback) {
    Object value = configuration.get(key);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof Number number) || number.intValue() < 1) {
      throw new IllegalArgumentException(key + " must be a positive integer");
    }
    return number.intValue();
  }

  private static String text(Map<String, Object> configuration, String key) {
    Object value = configuration.get(key);
    if (value instanceof String text && !text.isBlank()) {
      return text;
    }
    return null;
  }
}
