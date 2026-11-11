package dev.radeonium.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for Radeonium.
 *
 * Keep initialization lightweight. Hardware inspection and any future
 * rendering hooks should be isolated behind capability checks.
 */
public final class RadeoniumClient implements ClientModInitializer {
    public static final String MOD_ID = "radeonium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("Radeonium {} initialized. Performance modules are not enabled yet.",
                getClass().getPackage().getImplementationVersion() == null
                        ? "development build"
                        : getClass().getPackage().getImplementationVersion());
    }
}
