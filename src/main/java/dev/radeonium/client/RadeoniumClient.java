package dev.radeonium.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.radeonium.client.chunk.ChunkBuildBudget;
import dev.radeonium.client.gpu.GpuDetector;
import dev.radeonium.client.memory.MemoryPressureMonitor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for Radeonium.
 */
public final class RadeoniumClient implements ClientModInitializer {
    public static final String MOD_ID = "radeonium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "main")
    );

    // On AZERTY, the physical key labelled W produces the US-layout Z scancode.
    private static final KeyMapping TOGGLE_MODE = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.radeonium.toggle_mode",
                    InputConstants.Type.KEYBOARD,
                    InputConstants.KEY_Z,
                    KEY_CATEGORY
            )
    );

    @Override
    public void onInitializeClient() {
        LOGGER.info("Radeonium client initialized.");

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> GpuDetector.detectAndLog());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (MemoryPressureMonitor.tickAndSample()) {
                LOGGER.info("JVM heap pressure: {}", MemoryPressureMonitor.current());
            }

            while (TOGGLE_MODE.consumeClick()) {
                boolean enabled = !ChunkBuildBudget.isEnabled();
                ChunkBuildBudget.setEnabled(enabled);

                if (client.player != null) {
                    client.player.sendSystemMessage(Component.translatable(
                            enabled ? "message.radeonium.enabled" : "message.radeonium.disabled"
                    ));
                }

                LOGGER.info("Radeonium optimizations {}", enabled ? "enabled" : "disabled");
            }
        });
    }
}
