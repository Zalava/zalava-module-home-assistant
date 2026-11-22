package org.zalava.modules.homeassistant;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded, authenticated client for a Zalava-managed Home Assistant instance.
 *
 * <p>The token is only ever sent as a bearer header; it is never logged or returned. Every response
 * is capped at a caller-chosen byte bound so a hostile or misconfigured instance cannot exhaust
 * Zalava memory.
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
    return request(path, null);
  }

  Response post(String path, Map<String, Object> data) {
    byte[] body = MAPPER.writeValueAsBytes(data);
    if (body.length > 65_536) {
      throw new HomeAssistantException("Home Assistant request exceeds the bounded limit");
    }
    return request(path, body);
  }

  private Response request(String path, byte[] body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    HttpRequest request =
        body == null
            ? builder.GET().build()
            : builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    BoundedBody subscriber = new BoundedBody(maxResponseBytes);
    CompletableFuture<HttpResponse<byte[]>> pending =
        http.sendAsync(request, ignored -> subscriber);
    try {
      HttpResponse<byte[]> response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
      return new Response(
          response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    } catch (ExecutionException | TimeoutException exception) {
      throw new HomeAssistantException("Home Assistant request failed or exceeded its deadline");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new HomeAssistantException("Home Assistant request was interrupted");
    } finally {
      pending.cancel(true);
      subscriber.cancel();
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

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean cancelled;

    BoundedBody(int maximum) {
      this.maximum = maximum;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription value) {
      subscription = value;
      if (cancelled) {
        value.cancel();
      } else {
        value.request(1);
      }
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maximum - output.size()) {
          result.completeExceptionally(
              new HomeAssistantException("Home Assistant response exceeds the bounded limit"));
          cancel();
          return;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public synchronized void onComplete() {
      result.complete(output.toByteArray());
    }

    synchronized void cancel() {
      cancelled = true;
      if (subscription != null) {
        subscription.cancel();
      }
    }
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
