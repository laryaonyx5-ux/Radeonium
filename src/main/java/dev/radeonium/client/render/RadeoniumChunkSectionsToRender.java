package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import dev.radeonium.client.RadeoniumClient;
import dev.radeonium.mixin.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds Radeonium's terrain submission list from the live visible section set.
 * It currently reuses Minecraft's compiled section buffers and vertex formats,
 * but Radeonium owns the section traversal, layer draw lists, ordering and
 * terrain/section uniform construction passed into the frame graph.
 */
public final class RadeoniumChunkSectionsToRender {
    private RadeoniumChunkSectionsToRender() {}

    /**
     * A copied mesh may replace the live slice only when every draw-critical
     * property agrees. Any mismatch deliberately takes the vanilla fallback.
     */
    private static boolean isCompatibleOwnedGeometry(
            MinecraftCompiledGeometryGpuCache.LayerBuffers owned,
            VertexFormat activeFormat,
            SectionMesh.SectionDraw sectionDraw
    ) {
        if (owned == null || owned.vertexBuffer() == null || owned.vertexBuffer().isClosed()
                || owned.vertexCount() <= 0
                || !activeFormat.toString().equals(owned.vertexFormat())
                || PrimitiveTopology.QUADS.toString().equals(owned.topology()) == false) {
            return false;
        }

        long requiredVertexBytes = (long) owned.vertexCount() * activeFormat.getVertexSize();
        if (owned.vertexBuffer().size() < requiredVertexBytes
                || owned.indexCount() != sectionDraw.indexCount()) {
            return false;
        }

        if (sectionDraw.hasCustomIndexBuffer()) {
            GpuBuffer indices = owned.indexBuffer();
            IndexType expectedType = sectionDraw.indexType();
            return indices != null && !indices.isClosed()
                    && expectedType != null
                    && expectedType == owned.indexType()
                    && owned.indexCount() > 0
                    && indices.size() >= (long) owned.indexCount() * expectedType.bytes;
        }

        // Non-custom section draws use Minecraft's sequential QUADS index buffer.
        return owned.indexBuffer() == null && owned.indexCount() == sectionDraw.indexCount();
    }


    private static boolean isCompatibleModelGeometry(
            MinecraftSectionGpuCache.LayerBuffers owned, VertexFormat activeFormat
    ) {
        if (owned == null || owned.vertexBuffer() == null || owned.vertexBuffer().isClosed()
                || owned.vertexCount() <= 0 || owned.indexCount() <= 0
                || !activeFormat.toString().equals(owned.vertexFormat())
                || !PrimitiveTopology.QUADS.toString().equals(owned.topology())) {
            return false;
        }
        return owned.vertexBuffer().size() >= (long) owned.vertexCount() * activeFormat.getVertexSize()
                && owned.vertexCount() % 4 == 0
                && owned.indexCount() == (owned.vertexCount() / 4) * 6;
    }

    public static ChunkSectionsToRender create(
            LevelRenderer renderer,
            Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder
    ) {
        LevelRendererAccessor access = (LevelRendererAccessor) renderer;
        Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position();
        SectionRenderDispatcher dispatcher = access.radeonium$getSectionRenderDispatcher();

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            draws.put(layer, new ArrayList<>());
        }

        ArrayList<DynamicGpuData.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        List<TranslucentSectionOrder.Entry<RenderPass.Draw<GpuBufferSlice[]>>> translucentDraws =
                new ArrayList<>();
        int largestDefaultIndexCount = 0;

        if (dispatcher != null) {
            OptionsRenderState options = access.radeonium$getOptionsRenderState();
            long now = Util.getMillis();
            long fadeDuration = Util.toMillis(options.chunkSectionFadeInTime);

            dispatcher.lock();
            try {
                List<SectionRenderDispatcher.RenderSection> visibleSections =
                        new ArrayList<>(access.radeonium$getVisibleSections());
                Set<SectionPos> currentVisible = new HashSet<>();
                for (SectionRenderDispatcher.RenderSection visible : visibleSections) {
                    net.minecraft.core.SectionPos position =
                            net.minecraft.core.SectionPos.of(visible.getSectionNode());
                    currentVisible.add(new SectionPos(position.x(), position.y(), position.z()));
                }
                net.minecraft.core.BlockPos cameraBlock = net.minecraft.core.BlockPos.containing(
                        cameraPosition.x, cameraPosition.y, cameraPosition.z);
                net.minecraft.core.SectionPos cameraMinecraftSection =
                        net.minecraft.core.SectionPos.of(cameraBlock);
                Set<SectionPos> portalVisible = RadeoniumClient.rendererPipeline()
                        .collectPortalVisibleSections(new SectionPos(
                                cameraMinecraftSection.x(), cameraMinecraftSection.y(),
                                cameraMinecraftSection.z()), currentVisible);

                for (SectionRenderDispatcher.RenderSection section : visibleSections) {
                    net.minecraft.core.SectionPos candidateMinecraftPosition =
                            net.minecraft.core.SectionPos.of(section.getSectionNode());
                    SectionPos candidatePosition = new SectionPos(
                            candidateMinecraftPosition.x(), candidateMinecraftPosition.y(),
                            candidateMinecraftPosition.z());
                    // If any section's compiled visibility is unavailable, retain
                    // the complete Minecraft list rather than risk missing terrain.
                    if (portalVisible != null && !portalVisible.contains(candidatePosition)) continue;
                    SectionMesh mesh = section.getSectionMesh();
                    if (mesh == null) {
                        RadeoniumClient.terrainRenderDiagnostics().recordSectionWithoutMesh();
                        continue;
                    }

                    int sectionInfoIndex = -1;
                    for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                        SectionMesh.SectionDraw sectionDraw = mesh.getSectionDraw(layer);
                        SectionRenderDispatcher.RenderSectionBufferSlice slice =
                                dispatcher.getRenderSectionSlice(mesh, layer);

                        if (sectionDraw == null || slice == null
                                || (sectionDraw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) {
                            continue;
                        }

                        if (sectionInfoIndex < 0) {
                            var origin = section.getRenderOrigin();
                            sectionInfoIndex = sectionInfos.size();
                            sectionInfos.add(new DynamicGpuData.ChunkSectionInfo(
                                    origin.getX(), origin.getY(), origin.getZ(),
                                    section.getVisibility(now, fadeDuration)
                            ));
                        }

                        VertexFormat format = layer.pipeline(false).getVertexFormatBinding(0);
                        int baseVertex;
                        int firstIndex = 0;
                        GpuBuffer vertexBuffer;
                        GpuBuffer indexBuffer = null;
                        IndexType indexType = null;
                        int indexCount = sectionDraw.indexCount();

                        net.minecraft.core.SectionPos minecraftPosition = net.minecraft.core.SectionPos.of(section.getSectionNode());
                        SectionPos radeoniumPosition = new SectionPos(
                                minecraftPosition.x(), minecraftPosition.y(), minecraftPosition.z());
                        MinecraftSectionGpuCache.LayerBuffers modelOwned =
                                RadeoniumClient.minecraftGpuCache().layerBuffers(
                                        radeoniumPosition, layer, cameraPosition);
                        MinecraftCompiledGeometryGpuCache.LayerBuffers owned =
                                RadeoniumClient.compiledGeometryGpuCache().layerBuffers(minecraftPosition, layer);

                        boolean compatibleModelGeometry = isCompatibleModelGeometry(modelOwned, format);
                        boolean compatibleOwnedGeometry = !compatibleModelGeometry
                                && isCompatibleOwnedGeometry(owned, format, sectionDraw);
                        TerrainRenderDiagnostics.Source drawSource;

                        if (compatibleModelGeometry) {
                            drawSource = TerrainRenderDiagnostics.Source.RADEONIUM_MODEL;
                            // Native BLOCK vertices use Minecraft's sequential QUADS index buffer.
                            vertexBuffer = modelOwned.vertexBuffer();
                            baseVertex = 0;
                            indexBuffer = null;
                            indexType = null;
                            indexCount = modelOwned.indexCount();
                            largestDefaultIndexCount = Math.max(largestDefaultIndexCount, indexCount);
                        } else if (compatibleOwnedGeometry) {
                            drawSource = TerrainRenderDiagnostics.Source.RADEONIUM_COMPILED_GEOMETRY;
                            // The copied MeshData is section-local, so its base vertex and first index are zero.
                            vertexBuffer = owned.vertexBuffer();
                            baseVertex = 0;
                            indexBuffer = owned.indexBuffer();
                            indexType = indexBuffer == null ? null : owned.indexType();
                            if (owned.indexCount() > 0) indexCount = owned.indexCount();
                            if (indexBuffer == null) {
                                largestDefaultIndexCount = Math.max(largestDefaultIndexCount, indexCount);
                            }
                        } else {
                            drawSource = TerrainRenderDiagnostics.Source.VANILLA_FALLBACK;
                            // A missing/stale upload never removes terrain: use the live vanilla slice.
                            baseVertex = (int) (slice.vertexBufferOffset() / format.getVertexSize());
                            vertexBuffer = slice.vertexBuffer();
                            if (sectionDraw.hasCustomIndexBuffer()) {
                                indexBuffer = slice.indexBuffer();
                                indexType = sectionDraw.indexType();
                                firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                            } else {
                                largestDefaultIndexCount = Math.max(
                                        largestDefaultIndexCount, sectionDraw.indexCount()
                                );
                            }
                        }

                        RadeoniumClient.terrainRenderDiagnostics().recordDraw(layer, drawSource);
                        final int uniformIndex = sectionInfoIndex;
                        RenderPass.Draw<GpuBufferSlice[]> draw = new RenderPass.Draw<>(
                                0,
                                vertexBuffer,
                                indexBuffer,
                                indexType,
                                firstIndex,
                                indexCount,
                                baseVertex,
                                (uniformSlices, uploader) ->
                                        uploader.setUniform("ChunkSection", uniformSlices[uniformIndex])
                        );
                        if (layer == ChunkSectionLayer.TRANSLUCENT) {
                            translucentDraws.add(new TranslucentSectionOrder.Entry<>(
                                    radeoniumPosition, draw));
                        } else {
                            draws.get(layer).add(draw);
                        }
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }

        if (respectTranslucentOrder) {
            draws.get(ChunkSectionLayer.TRANSLUCENT).addAll(
                    TranslucentSectionOrder.farToNear(translucentDraws, cameraPosition));
        } else {
            translucentDraws.forEach(entry ->
                    draws.get(ChunkSectionLayer.TRANSLUCENT).add(entry.draw()));
        }

        var atlas = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        GpuBufferSlice terrainUniform = RenderSystem.getDynamicUniforms().writeTerrainTransform(
                modelViewMatrix, atlas.getWidth(0), atlas.getHeight(0)
        );
        GpuBufferSlice[] sectionUniforms = RenderSystem.getDynamicUniforms().writeChunkSections(
                sectionInfos.toArray(new DynamicGpuData.ChunkSectionInfo[0])
        );

        if (largestDefaultIndexCount > 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
                    .requestIndexCount(largestDefaultIndexCount);
        }

        return new ChunkSectionsToRender.DrawSeparate(
                terrainUniform, draws, largestDefaultIndexCount, sectionUniforms
        );
    }
}
