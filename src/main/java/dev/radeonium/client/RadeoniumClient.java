package dev.radeonium.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.radeonium.client.chunk.ChunkBuildBudget;
import dev.radeonium.client.chunk.CpuWorkProfile;
import dev.radeonium.client.gpu.GpuDetector;
import dev.radeonium.client.memory.MemoryPressureMonitor;
import dev.radeonium.client.render.ChunkRenderPipeline;
import dev.radeonium.client.render.MinecraftSectionGpuCache;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint for Radeonium. */
public final class RadeoniumClient implements ClientModInitializer {
    public static final String MOD_ID = "radeonium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ChunkRenderPipeline RENDERER_PIPELINE = new ChunkRenderPipeline();
    private static final MinecraftSectionGpuCache MINECRAFT_GPU_CACHE = new MinecraftSectionGpuCache();

    public static ChunkRenderPipeline rendererPipeline() {
        return RENDERER_PIPELINE;
    }

    /** Section-model uploads are owned here and must be changed on the render thread. */
    public static MinecraftSectionGpuCache minecraftGpuCache() {
        return MINECRAFT_GPU_CACHE;
    }

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
        LOGGER.info("CPU profile: {} logical processors; up to {} synchronous chunk builds on fast frames.",
                CpuWorkProfile.availableLogicalProcessors(),
                CpuWorkProfile.maxSynchronousChunkBuilds());

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> GpuDetector.detectAndLog());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MINECRAFT_GPU_CACHE.close());

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