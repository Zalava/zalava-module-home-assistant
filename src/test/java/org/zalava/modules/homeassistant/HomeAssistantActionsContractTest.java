package org.zalava.modules.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.zalava.api.ZalavaOperationResult;
import org.zalava.api.testing.ConfigFixture;
import org.zalava.api.testing.ModuleContractKit;
import org.zalava.api.testing.ProviderFixture;
import tools.jackson.databind.json.JsonMapper;

/** Local HTTP fixtures exercise only the built artifact, never household devices. */
class HomeAssistantActionsContractTest {
  private static final String MODULE = "zalava-module-home-assistant";
  private static final JsonMapper JSON = new JsonMapper();
  private static final String CATALOG =
      """
      [{"domain":"light","services":{"turn_on":{"description":"Turn on",
        "target":{"entity":[{"domain":"light"}]},
        "fields":{"brightness":{"selector":{"number":{"min":0,"max":255}}},
                  "transition":{"required":true,"selector":{"number":{}}},
                  "flash":{"selector":{"boolean":{}}},
                  "name":{"selector":{"text":{}}}}}}}]
      """;
  private HttpServer server;
  private ExecutorService executor;
  private ModuleContractKit kit;
  private ProviderFixture providers;
  private final AtomicReference<String> catalog = new AtomicReference<>(CATALOG);
  private final AtomicReference<String> state =
      new AtomicReference<>("{\"entity_id\":\"light.room\",\"state\":\"off\"}");
  private final AtomicReference<String> response = new AtomicReference<>("[]");
  private final AtomicInteger status = new AtomicInteger(200);
  private final AtomicInteger posts = new AtomicInteger();
  private final AtomicReference<String> posted = new AtomicReference<>();
  private final AtomicReference<String> uri = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private volatile boolean stallBody;
  private volatile boolean stallHeaders;

  @BeforeEach
  void open() throws Exception {
    kit =
        ModuleContractKit.load(
            Path.of(System.getProperty("module.artifact")),
            List.of(),
            MODULE,
            System.getProperty("module.version"));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/api/", this::handle);
    server.start();
    providers = kit.providers(configuration(8192));
  }

  @AfterEach
  void close() throws Exception {
    if (providers != null) {
      providers.close();
    }
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    if (kit != null) {
      kit.close();
    }
  }

  private ConfigFixture configuration(int maximum) {
    return ConfigFixture.empty()
        .factoryConfiguration(
            MODULE,
            "home-assistant",
            Map.of(
                "baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "tokenRef",
                "ha-test",
                "requestTimeoutSeconds",
                1,
                "maxResponseBytes",
                maximum))
        .secrets(MODULE, ref -> Optional.of("private-fixture-token".toCharArray()));
  }

  @Test
  void discoversMetadataThenExecutesAuthenticatedJsonWithoutClaimingPhysicalProof() {
    var discovery = providers.invoke("home-assistant", "home_assistant_actions", Map.of());
    assertThat(discovery.success()).isTrue();
    assertThat(discovery.content().toString())
        .contains("brightness", "Turn on", "light", "turn_on");
    var result = execute(Map.of("flash", true, "name", "Room", "brightness", 200, "transition", 1));
    assertThat(result.success()).isTrue();
    assertThat(result.content())
        .isEqualTo(
            Map.of(
                "domain",
                "light",
                "action",
                "turn_on",
                "statusCode",
                200,
                "accepted",
                true,
                "physicalOutcomeVerified",
                false));
    assertThat(JSON.readTree(posted.get()))
        .isEqualTo(
            JSON.readTree(
                "{\"entity_id\":\"light.room\",\"transition\":1,\"brightness\":200,\"flash\":true,\"name\":\"Room\"}"));
    assertThat(uri.get()).isEqualTo("/api/services/light/turn_on");
    assertThat(authorization.get()).isEqualTo("Bearer private-fixture-token");
    assertThat(posts).hasValue(1);
  }

  @Test
  void supportsDocumentedLegacyDiscoveryAndUntargetedActions() {
    catalog.set("[{\"domain\":\"custom\",\"services\":[\"run\"]}]");
    response.set("{\"changed_states\":[],\"service_response\":{}}");
    assertThat(providers.invoke("home-assistant", "home_assistant_actions", Map.of()).success())
        .isTrue();
    var args = Map.<String, Object>of("domain", "custom", "action", "run", "returnResponse", true);
    assertThat(invoke(args).success()).isTrue();
    assertThat(uri.get()).isEqualTo("/api/services/custom/run?return_response");
    assertThat(posted.get()).isEqualTo("{}");
  }

  @Test
  void revalidatesPreviouslyDiscoveredActionsBeforePosting() {
    assertThat(providers.invoke("home-assistant", "home_assistant_actions", Map.of()).success())
        .isTrue();
    catalog.set("[]");
    assertFailure(execute(Map.of("transition", 1)), "no longer available");
    assertThat(posts).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "",
        "not-json",
        "[{\"domain\":\"../escape\",\"services\":{}}]",
        "[{\"domain\":\"light\",\"services\":false}]",
        "[{\"domain\":\"light\",\"services\":{\"turn_on\":null}}]",
        "[{\"domain\":\"light\",\"services\":{\"../escape\":{}}}]"
      })
  void rejectsInvalidDiscoveryWithoutDispatch(String value) {
    catalog.set(value);
    assertFailure(execute(Map.of("transition", 1)), "");
    assertThat(posts).hasValue(0);
  }

  @Test
  void rejectsMalformedAndUnscopableArgumentsBeforeDispatch() {
    for (Map<String, Object> change :
        List.of(
            Map.<String, Object>of("domain", "http://other"),
            Map.<String, Object>of("action", "../turn_on"),
            Map.<String, Object>of("entityId", "all"),
            Map.<String, Object>of("entityId", List.of("light.room")),
            Map.<String, Object>of("entityId", "light.room,light.other"),
            Map.<String, Object>of("returnResponse", "yes"),
            Map.<String, Object>of("url", "http://other"),
            Map.<String, Object>of("serviceData", List.of()),
            Map.<String, Object>of(
                "serviceData", Map.of("transition", 1, "name", "x".repeat(60_001))))) {
      Map<String, Object> args = arguments(Map.of("transition", 1));
      args.putAll(change);
      assertFailure(invoke(args), "");
    }
    assertFailure(invoke(Map.of()), "Invalid domain");
    Map<String, Object> missingTarget = arguments(Map.of("transition", 1));
    missingTarget.remove("entityId");
    assertFailure(invoke(missingTarget), "explicit entityId");
    assertThat(posts).hasValue(0);
  }

  @Test
  void validatesRequiredFieldsTypesAndTargetOverrides() {
    for (String target :
        List.of("entity_id", "area_id", "device_id", "floor_id", "label_id", "target")) {
      assertFailure(execute(Map.of("transition", 1, target, "all")), "single explicit target");
    }
    assertFailure(execute(Map.of()), "required");
    assertFailure(execute(Map.of("transition", "one")), "numeric");
    assertFailure(execute(Map.of("transition", 1, "flash", 1)), "boolean");
    assertFailure(execute(Map.of("transition", 1, "name", 1)), "text");
    assertFailure(execute(Map.of("transition", 1, "brightness", -1)), "minimum");
    assertFailure(execute(Map.of("transition", 1, "brightness", 256)), "maximum");
    assertFailure(execute(Map.of("transition", 1, "unknown", 1)), "undescribed");
    Map<String, Object> nullValue = new HashMap<>();
    nullValue.put("transition", null);
    assertFailure(execute(nullValue), "null");
    assertThat(posts).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"entity_id\":\"light.other\"}",
        "{\"entity_id\":\"light.room\",\"state\":\"unavailable\"}",
        "{\"entity_id\":\"light.room\",\"state\":\"unknown\"}"
      })
  void rejectsMissingAndUnavailableTargets(String value) {
    state.set(value);
    assertFailure(execute(Map.of("transition", 1)), "Target entity");
    assertThat(posts).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 400, 401, 403, 404, 500})
  void reportsSafeHttpFailureWithoutRetryingOrReflectingErrorBody(int code) {
    status.set(code);
    response.set("private-fixture-token raw HA error");
    assertFailure(execute(Map.of("transition", 1)), "HTTP " + code);
    assertThat(posts).hasValue(1);
  }

  @Test
  void boundsResponseAndDiscoveryBodies() throws Exception {
    response.set("x".repeat(10_000));
    assertFailure(execute(Map.of("transition", 1)), "outcome may be unknown");
    assertThat(posts).hasValue(1);
    catalog.set("x".repeat(10_000));
    assertFailure(execute(Map.of("transition", 1)), "request failed");
    assertThat(posts).hasValue(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-json", "{}", "null"})
  void treatsMalformedSuccessfulPostResponsesAsUncertain(String body) {
    response.set(body);
    assertFailure(execute(Map.of("transition", 1)), "outcome may be unknown");
    assertThat(posts).hasValue(1);
  }

  @Test
  void requiresTheLegacyExplicitEntityField() {
    catalog.set(
        "[{\"domain\":\"light\",\"services\":{\"turn_on\":{\"fields\":{\"entity_id\":{\"required\":true}}}}}]");
    assertThat(execute(Map.of()).success()).isTrue();
    assertFailure(invoke(Map.of("domain", "light", "action", "turn_on")), "explicit entityId");
    assertThat(posts).hasValue(1);
  }

  @Test
  void capsCatalogEntries() {
    catalog.set(
        "[{\"domain\":\"custom\",\"services\":["
            + String.join(",", Collections.nCopies(2001, "\"run\""))
            + "]}]");
    // Raise the byte cap so this specifically exercises the catalog count bound.
    providers.close();
    providers = kit.providers(configuration(50_000));
    assertFailure(execute(Map.of("transition", 1)), "discovery exceeds");
  }

  @Test
  void timesOutEvenAfterHeadersHaveArrived() {
    stallBody = true;
    long start = System.nanoTime();
    assertFailure(execute(Map.of("transition", 1)), "outcome may be unknown");
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4000);
    assertThat(posts).hasValue(1);
  }

  @Test
  void timesOutBeforeHeadersWithoutAutomaticRetry() {
    stallHeaders = true;
    assertFailure(execute(Map.of("transition", 1)), "outcome may be unknown");
    assertThat(posts).hasValue(1);
  }

  private ZalavaOperationResult execute(Map<String, Object> data) {
    return invoke(arguments(data));
  }

  private Map<String, Object> arguments(Map<String, Object> data) {
    return new HashMap<>(
        Map.of(
            "domain", "light", "action", "turn_on", "entityId", "light.room", "serviceData", data));
  }

  private ZalavaOperationResult invoke(Map<String, Object> args) {
    return providers.invoke("home-assistant", "home_assistant_execute_action", args);
  }

  private void assertFailure(ZalavaOperationResult result, String fragment) {
    assertThat(result.success()).isFalse();
    assertThat(result.content().toString())
        .contains(fragment)
        .doesNotContain("private-fixture-token", "raw HA error");
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if ("/api/services".equals(path)) {
      respond(exchange, 200, catalog.get());
      return;
    }
    if (path.startsWith("/api/states/")) {
      respond(exchange, 200, state.get());
      return;
    }
    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
    posts.incrementAndGet();
    posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    uri.set(exchange.getRequestURI().toString());
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    if (stallHeaders) {
      pause();
    }
    if (stallBody) {
      exchange.sendResponseHeaders(200, 0);
      exchange.getResponseBody().write('[');
      exchange.getResponseBody().flush();
      pause();
      exchange.close();
      return;
    }
    exchange
        .getResponseHeaders()
        .set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/redirected");
    respond(exchange, status.get(), response.get());
  }

  private static void pause() {
    try {
      Thread.sleep(5000);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private static void respond(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(code, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
