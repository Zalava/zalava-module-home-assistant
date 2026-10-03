package org.zalava.modules.homeassistant;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.zalava.api.ModuleConfigurationDescriptor;
import org.zalava.api.ModuleDescriptor;
import org.zalava.api.ProviderFactory;
import org.zalava.api.ZalavaModule;
import org.zalava.api.extensions.managed.ManagedServiceDeclaration;

/** External Zalava module for a Zalava-managed Home Assistant instance. */
public final class HomeAssistantZalavaModule implements ZalavaModule {

  public static final String MODULE_ID = "zalava-module-home-assistant";

  @Override
  public ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE_ID,
        version(),
        "Home Assistant",
        "Bounded read-only Home Assistant status, entity discovery and managed-service declaration");
  }

  @Override
  public List<ProviderFactory> providerFactories() {
    return List.of(new HomeAssistantProviderFactory());
  }

  @Override
  public ModuleConfigurationDescriptor configuration() {
    return new ModuleConfigurationDescriptor(
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "properties",
            Map.of(
                "home-assistant",
                Map.of(
                    "type",
                    "object",
                    "additionalProperties",
                    false,
                    "required",
                    List.of("baseUrl", "tokenRef"),
                    "properties",
                    Map.of(
                        "baseUrl",
                        Map.of(
                            "type", "string",
                            "title", "Loopback base URL",
                            "description",
                                "Loopback base URL of the Zalava-managed Home Assistant instance, e.g. http://127.0.0.1:8123"),
                        "tokenRef",
                        Map.of(
                            "type", "string",
                            "title", "Token reference",
                            "description",
                                "Secret reference for the scoped Home Assistant long-lived access token"),
                        "requestTimeoutSeconds",
                        Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "title", "Request timeout (seconds)"),
                        "maxResponseBytes",
                        Map.of(
                            "type", "integer",
                            "minimum", 1,
                            "title", "Maximum response bytes"))))));
  }

  @Override
  public List<ManagedServiceDeclaration> managedServices() {
    return List.of(
        new ManagedServiceDeclaration(
            HomeAssistantManagedService.RESOURCE_ID, HomeAssistantManagedService.desiredState()));
  }

  static String version() {
    Properties properties = new Properties();
    try (InputStream input =
        HomeAssistantZalavaModule.class.getResourceAsStream("/module.properties")) {
      if (input == null) {
        throw new IllegalStateException("Missing module version metadata");
      }
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read module version metadata", exception);
    }
    return properties.getProperty("module.version");
  }
}
