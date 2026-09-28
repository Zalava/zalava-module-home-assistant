package org.zalava.modules.homeassistant;

import java.util.List;
import java.util.Optional;
import org.zalava.ProviderFactory;
import org.zalava.ProviderFactoryContext;
import org.zalava.ProviderFactoryDescriptor;
import org.zalava.SeaProvider;

/** Creates the bounded Home Assistant provider from scoped configuration and secrets. */
final class HomeAssistantProviderFactory implements ProviderFactory {

  @Override
  public ProviderFactoryDescriptor descriptor() {
    return new ProviderFactoryDescriptor(
        "home-assistant",
        HomeAssistantSeaModule.MODULE_ID,
        "home-automation",
        "Home Assistant",
        "Bounded read-only Home Assistant status, entity discovery and managed-service declaration");
  }

  @Override
  public List<SeaProvider> createProviders(ProviderFactoryContext context) {
    if (!HomeAssistantConfiguration.isConfigured(context.configuration())) {
      return List.of();
    }
    String tokenReference =
        HomeAssistantConfiguration.requiredText(context.configuration(), "tokenRef");
    Optional<char[]> token = context.secrets().resolve(tokenReference);
    if (token.isEmpty()) {
      return List.of();
    }
    HomeAssistantConfiguration configuration =
        HomeAssistantConfiguration.from(context.configuration());
    HomeAssistantClient client =
        new HomeAssistantClient(
            configuration.baseUrl(),
            token.get(),
            configuration.connectTimeout(),
            configuration.requestTimeout(),
            configuration.maxResponseBytes());
    return List.of(new HomeAssistantProvider(client));
  }
}
