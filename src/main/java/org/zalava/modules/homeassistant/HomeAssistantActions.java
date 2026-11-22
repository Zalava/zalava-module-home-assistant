package org.zalava.modules.homeassistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Live discovery and validation; actor policy and approvals belong to the host. */
final class HomeAssistantActions {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> ARGUMENTS =
      Set.of("domain", "action", "entityId", "serviceData", "returnResponse");
  private static final Set<String> TARGET_KEYS =
      Set.of("entity_id", "area_id", "device_id", "floor_id", "label_id", "target");
  private final HomeAssistantClient client;

  HomeAssistantActions(HomeAssistantClient client) {
    this.client = client;
  }

  List<Map<String, Object>> discover() {
    JsonNode catalog = read(client.get("/api/services"), "services");
    require(catalog.isArray(), "Home Assistant services were not an array");
    List<Map<String, Object>> actions = new ArrayList<>();
    for (JsonNode group : catalog) {
      String domain = identifier(group.path("domain"), "discovered domain");
      JsonNode services = group.path("services");
      require(services.isObject() || services.isArray(), "Invalid services discovery");
      if (services.isObject()) {
        for (String action : services.propertyNames()) {
          add(actions, domain, action, services.get(action));
        }
      } else {
        for (JsonNode action : services) {
          add(actions, domain, identifier(action, "discovered action"), MAPPER.createObjectNode());
        }
      }
    }
    return List.copyOf(actions);
  }

  private void add(
      List<Map<String, Object>> actions, String domain, String action, JsonNode metadata) {
    require(action.matches("[a-z0-9_]{1,128}"), "Invalid discovered action");
    require(metadata.isObject(), "Invalid action metadata");
    require(actions.size() < 2000, "Home Assistant action discovery exceeds the bounded limit");
    actions.add(
        Map.of(
            "domain",
            domain,
            "action",
            action,
            "metadata",
            MAPPER.convertValue(metadata, new TypeReference<Map<String, Object>>() {})));
  }

  Map<String, Object> execute(JsonNode arguments) {
    require(arguments != null && arguments.isObject(), "Action arguments must be an object");
    for (String key : arguments.propertyNames()) {
      require(ARGUMENTS.contains(key), "Unknown action argument");
    }
    String domain = identifier(arguments.path("domain"), "domain");
    String action = identifier(arguments.path("action"), "action");
    JsonNode data =
        arguments.has("serviceData") ? arguments.get("serviceData") : MAPPER.createObjectNode();
    require(data.isObject(), "serviceData must be an object");
    require(
        MAPPER.writeValueAsBytes(data).length <= 60_000, "Service data exceeds the bounded limit");
    for (String key : data.propertyNames()) {
      require(!TARGET_KEYS.contains(key), "Use entityId for a single explicit target");
    }
    boolean returnResponse = false;
    if (arguments.has("returnResponse")) {
      require(arguments.get("returnResponse").isBoolean(), "returnResponse must be a boolean");
      returnResponse = arguments.get("returnResponse").asBoolean();
    }
    String entity = null;
    if (arguments.has("entityId")) {
      JsonNode value = arguments.get("entityId");
      require(
          value.isString() && value.asString().matches("[a-z0-9_]{1,128}\\.[a-z0-9_]{1,128}"),
          "entityId must be one explicit entity ID");
      entity = value.asString();
    }

    // Never rely on a cached catalog: actions may have disappeared since discovery.
    Map<String, Object> selected =
        discover().stream()
            .filter(item -> domain.equals(item.get("domain")) && action.equals(item.get("action")))
            .findFirst()
            .orElseThrow(() -> new HomeAssistantException("Action is no longer available"));
    JsonNode metadata = MAPPER.valueToTree(selected.get("metadata"));
    validateData(data, metadata.path("fields"));
    require(
        !(metadata.has("target")
                || metadata.path("fields").path("entity_id").path("required").asBoolean(false))
            || entity != null,
        "This action requires an explicit entityId");
    if (entity != null) {
      JsonNode state = read(client.get("/api/states/" + entity), "target state");
      require(
          entity.equals(state.path("entity_id").asString("")), "Target entity is not available");
      require(
          !Set.of("unavailable", "unknown").contains(state.path("state").asString("")),
          "Target entity is unavailable");
    }
    Map<String, Object> body =
        MAPPER.convertValue(data, new TypeReference<Map<String, Object>>() {});
    body = new LinkedHashMap<>(body);
    if (entity != null) {
      body.put("entity_id", entity);
    }
    try {
      HomeAssistantClient.Response response =
          client.post(
              "/api/services/" + domain + "/" + action + (returnResponse ? "?return_response" : ""),
              body);
      require(response.ok(), "Action POST returned HTTP " + response.statusCode());
      JsonNode result = read(response, "action response");
      require(
          returnResponse ? result.isObject() : result.isArray(),
          "Invalid Home Assistant action response shape");
      // HA's changed-state list may include unrelated changes. Do not expose it as proof.
      return Map.of(
          "domain",
          domain,
          "action",
          action,
          "statusCode",
          response.statusCode(),
          "accepted",
          true,
          "physicalOutcomeVerified",
          false);
    } catch (HomeAssistantException exception) {
      throw new HomeAssistantException(
          exception.getMessage()
              + "; action outcome may be unknown. Observe state before retrying.");
    }
  }

  private static void validateData(JsonNode data, JsonNode fields) {
    for (String key : data.propertyNames()) {
      require(fields.has(key), "Unknown or undescribed service data field");
      JsonNode selector = fields.path(key).path("selector");
      JsonNode value = data.get(key);
      require(!value.isNull(), "Service data fields must not be null");
      if (selector.has("number")) {
        require(value.isNumber(), "Service data field must be numeric");
        JsonNode limits = selector.path("number");
        if (limits.path("min").isNumber()) {
          require(
              value.asDouble() >= limits.path("min").asDouble(),
              "Service data field is below its minimum");
        }
        if (limits.path("max").isNumber()) {
          require(
              value.asDouble() <= limits.path("max").asDouble(),
              "Service data field exceeds its maximum");
        }
      }
      if (selector.has("boolean")) {
        require(value.isBoolean(), "Service data field must be boolean");
      }
      if (selector.has("text")) {
        require(value.isString(), "Service data field must be text");
      }
    }
    if (fields.isObject()) {
      for (String key : fields.propertyNames()) {
        if (!"entity_id".equals(key) && fields.path(key).path("required").asBoolean(false)) {
          require(data.has(key), "Missing required service data field");
        }
      }
    }
  }

  private static JsonNode read(HomeAssistantClient.Response response, String purpose) {
    require(
        response.ok(), "Home Assistant " + purpose + " unavailable: HTTP " + response.statusCode());
    try {
      JsonNode value = MAPPER.readTree(response.body());
      require(value != null, "Empty Home Assistant JSON response");
      return value;
    } catch (JacksonException exception) {
      throw new HomeAssistantException("Invalid Home Assistant JSON response");
    }
  }

  private static String identifier(JsonNode value, String label) {
    require(value.isString() && value.asString().matches("[a-z0-9_]{1,128}"), "Invalid " + label);
    return value.asString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new HomeAssistantException(message);
    }
  }
}
