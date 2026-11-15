package dev.radeonium.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.radeonium.client.chunk.ChunkBuildBudget;
import dev.radeonium.client.chunk.CpuWorkProfile;
import dev.radeonium.client.gpu.GpuDetector;
import dev.radeonium.client.memory.MemoryPressureMonitor;
import dev.radeonium.client.render.ChunkRenderPipeline;
import dev.radeonium.client.render.MinecraftSectionGpuCache;
import dev.radeonium.client.render.MinecraftCompiledGeometryStore;
import dev.radeonium.client.render.MinecraftCompiledGeometryGpuCache;
import dev.radeonium.client.render.MinecraftSectionModelCaptureStore;
import dev.radeonium.client.render.RendererSectionTracker;
import dev.radeonium.client.render.SectionMeshingExecutor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entrypoint for Radeonium. */
public final class RadeoniumClient implements ClientModInitializer {
    public static final String MOD_ID = "radeonium";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ChunkRenderPipeline RENDERER_PIPELINE = new ChunkRenderPipeline();
    private static final MinecraftSectionGpuCache MINECRAFT_GPU_CACHE = new MinecraftSectionGpuCache();
    private static final SectionMeshingExecutor SECTION_MESHING_EXECUTOR = new SectionMeshingExecutor();
    private static final MinecraftCompiledGeometryGpuCache COMPILED_GEOMETRY_GPU_CACHE =
            new MinecraftCompiledGeometryGpuCache();
    private static ClientLevel trackedWorld;
    // Match the CPU/GPU section-cache bound: worker bursts must never create an unbounded backlog.
    private static final int MAX_PENDING_GEOMETRY_UPLOADS = 128;
    private static final ArrayBlockingQueue<MinecraftCompiledGeometryStore.Snapshot> PENDING_GEOMETRY_UPLOADS =
            new ArrayBlockingQueue<>(MAX_PENDING_GEOMETRY_UPLOADS);

    public static void queueCompiledGeometryUpload(MinecraftCompiledGeometryStore.Snapshot snapshot) {
        if (snapshot == null) return;
        // Keep the newest compiled sections when workers outpace the render-thread upload budget.
        while (!PENDING_GEOMETRY_UPLOADS.offer(snapshot)) {
            PENDING_GEOMETRY_UPLOADS.poll();
        }
    }

    private static void drainCompiledGeometryUploads() {
        // Bound per-tick work so a burst of chunk compilation cannot monopolize a frame.
        for (int i = 0; i < 4; i++) {
            MinecraftCompiledGeometryStore.Snapshot snapshot = PENDING_GEOMETRY_UPLOADS.poll();
            if (snapshot == null) break;
            COMPILED_GEOMETRY_GPU_CACHE.upload(snapshot);
        }
    }

    public static SectionMeshingExecutor sectionMeshingExecutor() { return SECTION_MESHING_EXECUTOR; }

    public static MinecraftCompiledGeometryGpuCache compiledGeometryGpuCache() {
        return COMPILED_GEOMETRY_GPU_CACHE;
    }

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

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.fromNamespaceAndPath(MOD_ID, "section_model_capture_invalidation");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager resourceManager) {
                        // Baked quads and materials are tied to the current model/atlas reload.
                        MinecraftSectionModelCaptureStore.clear();
                    }
                }
        );

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> GpuDetector.detectAndLog());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SECTION_MESHING_EXECUTOR.close();
            MINECRAFT_GPU_CACHE.close();
            COMPILED_GEOMETRY_GPU_CACHE.close();
            PENDING_GEOMETRY_UPLOADS.clear();
            MinecraftCompiledGeometryStore.clear();
            MinecraftSectionModelCaptureStore.clear();
            RendererSectionTracker.reset();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            drainCompiledGeometryUploads();
            // Fabric API versions used by this project do not expose a world-unload event.
            // Detect level identity changes on the client tick instead.
            if (client.level != trackedWorld) {
                if (trackedWorld != null) {
                    RendererSectionTracker.reset();
                    MINECRAFT_GPU_CACHE.clear();
                    COMPILED_GEOMETRY_GPU_CACHE.clear();
                    PENDING_GEOMETRY_UPLOADS.clear();
                    MinecraftCompiledGeometryStore.clear();
                    MinecraftSectionModelCaptureStore.clear();
                }
                trackedWorld = client.level;
            }

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