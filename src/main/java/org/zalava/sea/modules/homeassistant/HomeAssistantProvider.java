package org.zalava.modules.homeassistant;

import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.zalava.InvocationContext;
import org.zalava.ProviderCapabilities;
import org.zalava.ProviderDescriptor;
import org.zalava.ZalavaOperationResult;
import org.zalava.ZalavaProvider;
import org.zalava.ZalavaToolDescriptor;

/**
 * Agent-facing provider exposing bounded, read-only Home Assistant status plus the managed-service
 * declaration. It offers no raw device administration and no shell or URL pass-through.
 */
final class HomeAssistantProvider implements ZalavaProvider {
  private static final String STATUS = "home_assistant_status";
  private static final String VERSION = "home_assistant_version";
  private static final String ENTITIES = "home_assistant_entities";
  private static final String MANAGED_SERVICE = "home_assistant_managed_service";
  private static final int MAX_ENTITIES = 500;

  private final HomeAssistantClient client;

  HomeAssistantProvider(HomeAssistantClient client) {
    this.client = client;
  }

  @Override
  public ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "home-assistant",
        HomeAssistantSeaModule.MODULE_ID,
        "home-automation",
        "Home Assistant",
        "Bounded read-only Home Assistant status, entity discovery and managed-service declaration",
        HomeAssistantSeaModule.version(),
        ProviderCapabilities.toolsOnly(),
        List.of("home", "home-automation"),
        Map.of());
  }

  @Override
  public ProviderCapabilities capabilities() {
    return descriptor().capabilities();
  }

  @Override
  public List<ZalavaToolDescriptor> listTools() {
    return List.of(
        tool(STATUS, "Reports whether the managed Home Assistant API is reachable."),
        tool(VERSION, "Returns the running Home Assistant version."),
        tool(
            ENTITIES,
            "Lists bounded discovered Home Assistant entities with id, state and friendly name."),
        tool(
            MANAGED_SERVICE,
            "Returns the digest-pinned managed-service declaration for administrator approval."));
  }

  @Override
  public ZalavaOperationResult callTool(
      String toolName, JsonNode arguments, InvocationContext context) {
    try {
      return switch (toolName) {
        case STATUS -> status();
        case VERSION -> ZalavaOperationResult.success(Map.of("version", client.version()));
        case ENTITIES -> entities();
        case MANAGED_SERVICE -> managedService();
        default -> ZalavaOperationResult.failure(Map.of("error", "Unknown tool: " + toolName));
      };
    } catch (HomeAssistantException exception) {
      return ZalavaOperationResult.failure(Map.of("error", exception.getMessage()));
    }
  }

  private ZalavaOperationResult status() {
    HomeAssistantClient.Response response = client.get("/api/");
    return ZalavaOperationResult.success(
        Map.of("statusCode", response.statusCode(), "reachable", response.ok()));
  }

  private ZalavaOperationResult entities() {
    List<Map<String, Object>> entities = client.entities(MAX_ENTITIES);
    return ZalavaOperationResult.success(Map.of("count", entities.size(), "entities", entities));
  }

  private ZalavaOperationResult managedService() {
    var desired = HomeAssistantManagedService.desiredState();
    Map<String, Object> declaration = new LinkedHashMap<>();
    declaration.put("configured", true);
    declaration.put("resourceId", desired.resourceId());
    declaration.put("artifactReference", desired.artifactReference());
    declaration.put("revision", desired.revision());
    declaration.put("lifecycle", desired.lifecycle().name());
    declaration.put("ports", List.copyOf(desired.ports()));
    declaration.put("dataPaths", List.copyOf(desired.dataPaths()));
    declaration.put("secretReferences", List.copyOf(desired.secretReferences()));
    declaration.put("devices", List.copyOf(desired.devices()));
    declaration.put("administratorApprovalRequired", true);
    return ZalavaOperationResult.success(Map.copyOf(declaration));
  }

  private static ZalavaToolDescriptor tool(String name, String description) {
    return new ZalavaToolDescriptor(
        name, description, false, List.of("home"), Map.of("type", "object"));
  }
}
