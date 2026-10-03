package org.zalava.modules.homeassistant;

import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.zalava.api.extensions.managed.ManagedServiceDesiredState;
import org.zalava.api.extensions.managed.ManagedServiceLifecycle;
import org.zalava.api.extensions.managed.ManagedServiceLimits;

/**
 * Zalava-managed declaration of the pinned Home Assistant Container.
 *
 * <p>This is a module-declared desired state only: Zalava administrators still approve the matching
 * resource grant and execute the install. The container publishes only loopback port 8123, owns a
 * managed configuration volume, requests no host devices, and is never granted the engine socket.
 */
final class HomeAssistantManagedService {
  static final String RESOURCE_ID = "home-assistant";
  static final String IMAGE_REPOSITORY = "ghcr.io/home-assistant/home-assistant";
  static final int LOOPBACK_PORT = 8123;
  static final String CONFIG_DATA_PATH = "/var/lib/zalava/managed/home-assistant/config";
  static final String TOKEN_SECRET_REFERENCE = "home-assistant-token";

  /**
   * The upstream Home Assistant Container release this module release supports. The module owns the
   * pin: admins approve the derived grant, not an arbitrary image, and updating the provider means
   * releasing a new module version.
   */
  static final String PINNED_IMAGE_DIGEST =
      "612d76760b544cb40b7ba01387fdac964c59a6a550a50a4d30b4773c822d2918";

  static final String PINNED_IMAGE_REVISION = "2026.9.1";
  private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

  private HomeAssistantManagedService() {}

  /** The declared service for this module release, pinned to the supported upstream digest. */
  static ManagedServiceDesiredState desiredState() {
    return desiredState(PINNED_IMAGE_DIGEST, PINNED_IMAGE_REVISION);
  }

  static ManagedServiceDesiredState desiredState(String imageDigest, String revision) {
    if (imageDigest == null || !DIGEST.matcher(imageDigest).matches()) {
      throw new IllegalArgumentException("imageDigest must be a lowercase sha256 hex digest");
    }
    if (revision == null || revision.isBlank()) {
      throw new IllegalArgumentException("revision must not be blank");
    }
    return new ManagedServiceDesiredState(
        RESOURCE_ID,
        IMAGE_REPOSITORY + "@sha256:" + imageDigest,
        revision,
        ManagedServiceLifecycle.RUNNING,
        Set.of(TOKEN_SECRET_REFERENCE),
        Set.of(CONFIG_DATA_PATH),
        Set.of(LOOPBACK_PORT),
        Set.of(),
        new ManagedServiceLimits(2000, 1_073_741_824, 256),
        Duration.ofMinutes(2),
        3);
  }
}
