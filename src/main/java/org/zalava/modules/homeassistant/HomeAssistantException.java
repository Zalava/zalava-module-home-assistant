package org.zalava.modules.homeassistant;

/** Typed failure for bounded Home Assistant access and managed-service declaration errors. */
final class HomeAssistantException extends RuntimeException {

  HomeAssistantException(String message) {
    super(message);
  }

  HomeAssistantException(String message, Throwable cause) {
    super(message, cause);
  }
}
