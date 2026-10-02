package org.zalava.modules.homeassistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded, authenticated read-only client for a SEA-managed Home Assistant instance.
 *
 * <p>The token is only ever sent as a bearer header; it is never logged or returned. Every response
 * is capped at a caller-chosen byte bound so a hostile or misconfigured instance cannot exhaust SEA
 * memory.
 */
final class HomeAssistantClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient http;
  private final URI baseUri;
  private final String token;
  private final Duration requestTimeout;
  private final int maxResponseBytes;

  HomeAssistantClient(
      URI baseUri,
      char[] token,
      Duration connectTimeout,
      Duration requestTimeout,
      int maxResponseBytes) {
    this.baseUri = normalize(baseUri);
    this.token = new String(token);
    this.requestTimeout = requestTimeout;
    this.maxResponseBytes = maxResponseBytes;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  Response get(String path) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(requestTimeout)
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<InputStream> response =
          http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = readBounded(input);
      }
      return new Response(response.statusCode(), new String(body, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new HomeAssistantException("Home Assistant request failed for " + path, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new HomeAssistantException(
          "Home Assistant request was interrupted for " + path, exception);
    }
  }

  String version() {
    Response response = get("/api/config");
    if (!response.ok()) {
      throw new HomeAssistantException(
          "Home Assistant config is unavailable: HTTP " + response.statusCode());
    }
    try {
      JsonNode node = MAPPER.readTree(response.body());
      String version = node.path("version").asString("");
      if (version.isBlank()) {
        throw new HomeAssistantException("Home Assistant config does not expose a version");
      }
      return version;
    } catch (JacksonException exception) {
      throw new HomeAssistantException("Home Assistant config was not valid JSON", exception);
    }
  }

  List<Map<String, Object>> entities(int maximum) {
    Response response = get("/api/states");
    if (!response.ok()) {
      throw new HomeAssistantException(
          "Home Assistant states are unavailable: HTTP " + response.statusCode());
    }
    try {
      JsonNode node = MAPPER.readTree(response.body());
      if (!node.isArray()) {
        throw new HomeAssistantException("Home Assistant states were not a JSON array");
      }
      List<Map<String, Object>> entities = new ArrayList<>();
      for (JsonNode state : node) {
        if (entities.size() >= maximum) {
          break;
        }
        String entityId = state.path("entity_id").asString("");
        if (entityId.isBlank()) {
          continue;
        }
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("entityId", entityId);
        entity.put("state", state.path("state").asString(""));
        String friendlyName = state.path("attributes").path("friendly_name").asString("");
        if (!friendlyName.isBlank()) {
          entity.put("friendlyName", friendlyName);
        }
        entities.add(entity);
      }
      return List.copyOf(entities);
    } catch (JacksonException exception) {
      throw new HomeAssistantException("Home Assistant states were not valid JSON", exception);
    }
  }

  private byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = input.read(buffer)) != -1) {
      total += read;
      if (total > maxResponseBytes) {
        throw new HomeAssistantException("Home Assistant response exceeds the bounded limit");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static URI normalize(URI baseUri) {
    String value = baseUri.toString();
    return URI.create(value.endsWith("/") ? value : value + "/");
  }

  record Response(int statusCode, String body) {
    boolean ok() {
      return statusCode >= 200 && statusCode < 300;
    }
  }
}
