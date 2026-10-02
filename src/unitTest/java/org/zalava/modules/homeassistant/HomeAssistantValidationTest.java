package org.zalava.modules.homeassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HomeAssistantValidationTest {
  @Test
  void rejectsIncompleteMalformedAndUnboundedConfiguration() {
    assertThat(HomeAssistantConfiguration.isConfigured(Map.of())).isFalse();
    assertThat(
            HomeAssistantConfiguration.isConfigured(
                Map.of("baseUrl", "http://localhost", "tokenRef", " ")))
        .isFalse();
    assertThatThrownBy(() -> HomeAssistantConfiguration.from(Map.of()))
        .hasMessageContaining("requires baseUrl");
    for (String uri : new String[] {"not a URI", "http:/missing-host", "ftp://host"}) {
      assertThatThrownBy(() -> HomeAssistantConfiguration.from(Map.of("baseUrl", uri)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (Object invalid : new Object[] {0, -1, "ten"}) {
      assertThatThrownBy(
              () ->
                  HomeAssistantConfiguration.from(
                      Map.of("baseUrl", "https://host/", "requestTimeoutSeconds", invalid)))
          .hasMessageContaining("requestTimeoutSeconds");
    }
    var configured =
        HomeAssistantConfiguration.from(
            Map.of("baseUrl", "https://host/", "requestTimeoutSeconds", 3, "maxResponseBytes", 64));
    assertThat(configured.requestTimeout()).hasSeconds(3);
    assertThat(configured.maxResponseBytes()).isEqualTo(64);
  }

  @Test
  void rejectsUnpinnedImagesAndEmptyRevisions() {
    for (String digest : new String[] {null, "short", "A".repeat(64)}) {
      assertThatThrownBy(() -> HomeAssistantManagedService.desiredState(digest, "2026.9.1"))
          .hasMessageContaining("imageDigest");
    }
    for (String revision : new String[] {null, " "}) {
      assertThatThrownBy(() -> HomeAssistantManagedService.desiredState("a".repeat(64), revision))
          .hasMessageContaining("revision");
    }
  }
}
