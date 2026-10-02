package org.zalava.modules.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.zalava.api.InvocationContext;
import org.zalava.api.ZalavaOperationResult;
import org.zalava.api.ZalavaProvider;
import org.zalava.api.ZalavaToolDescriptor;
import org.zalava.api.testing.ConfigFixture;
import org.zalava.api.testing.ModuleContractKit;
import org.zalava.api.testing.ProviderFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Exercises the real built module JAR at the stable {@code module-api} boundary through the
 * released contract kit. A loopback {@code HttpServer} stands in for the managed Home Assistant
 * instance so no real device is required. Host-owned resolution, validation, permissions and
 * persistence stay covered by SEA.
 */
class HomeAssistantSeaModuleTest {

  private static final String MODULE_ID = "zalava-module-home-assistant";
  private static final String FACTORY_ID = "home-assistant";
  private static final String PROVIDER_ID = "home-assistant";
  private static final String TOKEN_REFERENCE = "home-assistant-token";
  private static final String PINNED_DIGEST =
      "612d76760b544cb40b7ba01387fdac964c59a6a550a50a4d30b4773c822d2918";
  private static final String UNREACHABLE_BASE_URL = "http://127.0.0.1:1";

  private ModuleContractKit kit;
  private HttpServer server;

  @BeforeEach
  void loadTheBuiltArtifact() {
    kit =
        ModuleContractKit.load(
            Path.of(System.getProperty("module.artifact")),
            List.of(),
            MODULE_ID,
            System.getProperty("module.version"));
  }

  @AfterEach
  void closeTheArtifact() throws Exception {
    if (server != null) {
      server.stop(0);
    }
    if (kit != null) {
      kit.close();
    }
  }

  @Test
  void loadsTheModuleFromTheBuiltArtifact() {
    assertThat(kit.module().getClass().getClassLoader()).isNotSameAs(getClass().getClassLoader());
    assertThat(
            kit.module().getClass().getProtectionDomain().getCodeSource().getLocation().toString())
        .endsWith(".jar");
  }

  @Test
  void exposesTheModuleOwnedDescriptorAndFactoryContract() {
    assertThat(kit.moduleId()).isEqualTo(MODULE_ID);
    assertThat(kit.version()).isEqualTo(System.getProperty("module.version"));
    assertThat(kit.module().configuration().jsonSchema()).containsEntry("type", "object");

    assertThat(kit.module().providerFactories()).hasSize(1);
    var factory = kit.module().providerFactories().getFirst().descriptor();
    assertThat(factory.factoryId()).isEqualTo(FACTORY_ID);
    assertThat(factory.moduleId()).isEqualTo(MODULE_ID);
    assertThat(factory.providerType()).isEqualTo("home-automation");
  }

  @Test
  void declaresFactoryScopedConfigurationSchema() {
    Map<String, Object> schema = kit.module().configuration().jsonSchema();

    assertThat(schema).containsEntry("type", "object");
    assertThat(schema.get("properties"))
        .isInstanceOfSatisfying(
            Map.class, properties -> assertThat(properties).containsKey(FACTORY_ID));
  }

  @Test
  void createsTheConfiguredProviderAndDeclaresItsReadOnlyTools() {
    try (ProviderFixture providers = kit.providers(configuration(UNREACHABLE_BASE_URL, Map.of()))) {
      ZalavaProvider provider = providers.requireProvider(PROVIDER_ID);

      assertThat(provider.descriptor().providerId()).isEqualTo(PROVIDER_ID);
      assertThat(provider.descriptor().moduleId()).isEqualTo(MODULE_ID);
      assertThat(provider.descriptor().providerType()).isEqualTo("home-automation");
      assertThat(provider.listTools().stream().map(ZalavaToolDescriptor::name))
          .containsExactly(
              "home_assistant_status",
              "home_assistant_version",
              "home_assistant_entities",
              "home_assistant_managed_service");
      assertThat(provider.listTools()).allMatch(tool -> !tool.sideEffecting());
    }
  }

  @Test
  void reportsBoundedAuthenticatedStatusAndVersion() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    server =
        server(
            exchange -> {
              authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
              if ("/api/config".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "{\"version\":\"2026.9.1\",\"location_name\":\"Home\"}");
              } else {
                respond(exchange, 200, "{\"message\":\"API running.\"}");
              }
            });

    try (ProviderFixture providers = kit.providers(configuration(baseUrl(), Map.of()))) {
      assertThat(
              content(
                  providers.invoke(
                      PROVIDER_ID,
                      "home_assistant_status",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .containsEntry("statusCode", 200)
          .containsEntry("reachable", true);
      assertThat(
              content(
                  providers.invoke(
                      PROVIDER_ID,
                      "home_assistant_version",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))))
          .containsEntry("version", "2026.9.1");
    }

    assertThat(authorization.get()).isEqualTo("Bearer fixture-token");
  }

  @Test
  void listsBoundedDiscoveredEntities() throws Exception {
    server =
        server(
            exchange ->
                respond(
                    exchange,
                    200,
                    """
                    [
                      {"entity_id":"light.salon","state":"on","attributes":{"friendly_name":"Salon Lights"}},
                      {"entity_id":"media_player.fire_tv","state":"idle","attributes":{"friendly_name":"Fire TV"}},
                      {"entity_id":"climate.daikin","state":"off","attributes":{}}
                    ]
                    """));

    try (ProviderFixture providers = kit.providers(configuration(baseUrl(), Map.of()))) {
      Map<String, Object> result =
          content(
              providers.invoke(
                  PROVIDER_ID,
                  "home_assistant_entities",
                  new tools.jackson.databind.json.JsonMapper()
                      .convertValue(
                          arguments(),
                          new tools.jackson.core.type.TypeReference<
                              java.util.Map<String, Object>>() {})));
      assertThat(result).containsEntry("count", 3);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> entities = (List<Map<String, Object>>) result.get("entities");
      assertThat(entities)
          .extracting(entity -> entity.get("entityId"))
          .containsExactly("light.salon", "media_player.fire_tv", "climate.daikin");
      assertThat(entities.get(0))
          .containsEntry("state", "on")
          .containsEntry("friendlyName", "Salon Lights");
      assertThat(entities.get(2)).doesNotContainKey("friendlyName");
    }
  }

  @Test
  void declaresThePinnedManagedServiceThroughTheModuleAndTool() {
    assertThat(kit.module().managedServices()).hasSize(1);
    var declaration = kit.module().managedServices().getFirst();
    assertThat(declaration.serviceId()).isEqualTo("home-assistant");
    assertThat(declaration.desiredState().artifactReference())
        .isEqualTo("ghcr.io/home-assistant/home-assistant@sha256:" + PINNED_DIGEST);
    assertThat(declaration.desiredState().revision()).isEqualTo("2026.9.1");
    assertThat(declaration.desiredState().ports()).containsExactly(8123);
    assertThat(declaration.desiredState().secretReferences()).containsExactly(TOKEN_REFERENCE);
    assertThat(declaration.desiredState().dataPaths())
        .containsExactly("/var/lib/sea/managed/home-assistant/config");

    try (ProviderFixture providers = kit.providers(configuration(UNREACHABLE_BASE_URL, Map.of()))) {
      Map<String, Object> toolDeclaration =
          content(
              providers.invoke(
                  PROVIDER_ID,
                  "home_assistant_managed_service",
                  new tools.jackson.databind.json.JsonMapper()
                      .convertValue(
                          arguments(),
                          new tools.jackson.core.type.TypeReference<
                              java.util.Map<String, Object>>() {})));
      assertThat(toolDeclaration)
          .containsEntry("configured", true)
          .containsEntry("resourceId", "home-assistant")
          .containsEntry(
              "artifactReference", "ghcr.io/home-assistant/home-assistant@sha256:" + PINNED_DIGEST)
          .containsEntry("revision", "2026.9.1")
          .containsEntry("administratorApprovalRequired", true)
          .containsEntry("ports", List.of(8123))
          .containsEntry("devices", List.of())
          .containsEntry("secretReferences", List.of(TOKEN_REFERENCE));
    }
  }

  @Test
  void loadsWithoutProvidersUntilConfigured() {
    try (ProviderFixture providers = kit.providers(ConfigFixture.empty())) {
      assertThat(providers.providers()).isEmpty();
    }

    // baseUrl + tokenRef present but no secret configured yet.
    try (ProviderFixture providers =
        kit.providers(
            ConfigFixture.empty()
                .factoryConfiguration(
                    MODULE_ID,
                    FACTORY_ID,
                    Map.of("baseUrl", UNREACHABLE_BASE_URL, "tokenRef", TOKEN_REFERENCE)))) {
      assertThat(providers.providers()).isEmpty();
    }

    // tokenRef missing.
    try (ProviderFixture providers =
        kit.providers(configuration(UNREACHABLE_BASE_URL, Map.of(), false))) {
      assertThat(providers.providers()).isEmpty();
    }
  }

  @Test
  void rejectsAnInvalidConfiguredBaseUrl() {
    assertThatThrownBy(() -> kit.providers(configuration("ftp://host", Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void returnsFailureInsteadOfThrowingWhenUnreachableOrOversized() throws Exception {
    try (ProviderFixture providers =
        kit.providers(configuration(UNREACHABLE_BASE_URL, Map.of("requestTimeoutSeconds", 1)))) {
      assertThat(
              providers
                  .invoke(
                      PROVIDER_ID,
                      "home_assistant_status",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))
                  .success())
          .isFalse();
      assertThat(
              providers
                  .invoke(
                      PROVIDER_ID,
                      "home_assistant_version",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))
                  .success())
          .isFalse();
    }

    server = server(exchange -> respond(exchange, 200, "x".repeat(5000)));
    try (ProviderFixture providers =
        kit.providers(configuration(baseUrl(), Map.of("maxResponseBytes", 100)))) {
      assertThat(
              providers
                  .invoke(
                      PROVIDER_ID,
                      "home_assistant_version",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}))
                  .success())
          .isFalse();
    }

    try (ProviderFixture providers = kit.providers(configuration(UNREACHABLE_BASE_URL, Map.of()))) {
      ZalavaProvider provider = providers.requireProvider(PROVIDER_ID);
      assertThat(
              provider
                  .callTool(
                      "unknown_tool",
                      new tools.jackson.databind.json.JsonMapper()
                          .convertValue(
                              arguments(),
                              new tools.jackson.core.type.TypeReference<
                                  java.util.Map<String, Object>>() {}),
                      InvocationContext.system())
                  .success())
          .isFalse();
    }
  }

  private ConfigFixture configuration(String baseUrl, Map<String, Object> extra) {
    return configuration(baseUrl, extra, true);
  }

  private ConfigFixture configuration(
      String baseUrl, Map<String, Object> extra, boolean includeTokenRef) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("baseUrl", baseUrl);
    if (includeTokenRef) {
      values.put("tokenRef", TOKEN_REFERENCE);
    }
    values.putAll(extra);
    return ConfigFixture.empty()
        .factoryConfiguration(MODULE_ID, FACTORY_ID, values)
        .secrets(
            MODULE_ID,
            reference ->
                TOKEN_REFERENCE.equals(reference)
                    ? Optional.of("fixture-token".toCharArray())
                    : Optional.empty());
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static JsonNode arguments() {
    return JsonNodeFactory.instance.objectNode();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> content(ZalavaOperationResult result) {
    return (Map<String, Object>) result.content();
  }

  private interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  private HttpServer server(Handler handler) throws IOException {
    HttpServer http =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    http.createContext(
        "/api/",
        exchange -> {
          try {
            handler.handle(exchange);
          } catch (IOException | RuntimeException exception) {
            respond(exchange, 500, "{}");
          }
        });
    http.start();
    return http;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
