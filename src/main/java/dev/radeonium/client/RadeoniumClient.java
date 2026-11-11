package dev.radeonium.client;

import dev.radeonium.client.gpu.GpuDetector;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
        LOGGER.info("Radeonium client initialized.");

        // Minecraft creates its graphics device before CLIENT_STARTED.
        // Query it here rather than probing OpenGL during mod initialization.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> GpuDetector.detectAndLog());
    }
}
