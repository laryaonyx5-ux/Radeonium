package dev.radeonium.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Radeonium-owned terrain submission list.
 *
 * It deliberately consumes Minecraft's already-compiled, already-uploaded section
 * buffers for this first replacement stage. Radeonium now owns the visible-section
 * traversal, per-layer draw list, ordering, section uniforms and submission object;
 * Minecraft still owns block meshing and the underlying uber-buffer allocations.
 */
public final class RadeoniumChunkSectionsToRender extends ChunkSectionsToRender {
    private final EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws;
    private final GpuBufferSlice[] sectionUniforms;

    private RadeoniumChunkSectionsToRender(
            GpuBufferSlice terrainUniform,
            int maxIndicesRequired,
            EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws,
            GpuBufferSlice[] sectionUniforms
    ) {
        super(terrainUniform, maxIndicesRequired);
        this.draws = draws;
        this.sectionUniforms = sectionUniforms;
    }

    public static RadeoniumChunkSectionsToRender create(
            LevelRenderer renderer,
            Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder
    ) {
        LevelRendererAccessor access = (LevelRendererAccessor) renderer;
        SectionRenderDispatcher dispatcher = access.radeonium$getSectionRenderDispatcher();

        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> draws =
                new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            draws.put(layer, new ArrayList<>());
        }

        ArrayList<DynamicGpuData.ChunkSectionInfo> sectionInfos = new ArrayList<>();
        int largestDefaultIndexCount = 0;

        if (dispatcher != null) {
            OptionsRenderState options = access.radeonium$getOptionsRenderState();
            long now = Util.getMillis();
            long fadeDuration = Util.toMillis(options.chunkSectionFadeInTime);

            dispatcher.lock();
            try {
                for (SectionRenderDispatcher.RenderSection section : access.radeonium$getVisibleSections()) {
                    SectionMesh mesh = section.getSectionMesh();
                    if (mesh == null) continue;

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
                        int baseVertex = (int) (slice.vertexBufferOffset() / format.getVertexSize());
                        int firstIndex = 0;
                        GpuBuffer indexBuffer = null;
                        IndexType indexType = null;

                        if (sectionDraw.hasCustomIndexBuffer()) {
                            indexBuffer = slice.indexBuffer();
                            indexType = sectionDraw.indexType();
                            firstIndex = (int) (slice.indexBufferOffset() / indexType.bytes);
                        } else {
                            largestDefaultIndexCount = Math.max(
                                    largestDefaultIndexCount, sectionDraw.indexCount()
                            );
                        }

                        final int uniformIndex = sectionInfoIndex;
                        draws.get(layer).add(new RenderPass.Draw<>(
                                0,
                                slice.vertexBuffer(),
                                indexBuffer,
                                indexType,
                                firstIndex,
                                sectionDraw.indexCount(),
                                baseVertex,
                                (uniformSlices, uploader) ->
                                        uploader.setUniform("ChunkSection", uniformSlices[uniformIndex])
                        ));
                    }
                }
            } finally {
                dispatcher.unlock();
            }
        }

        if (respectTranslucentOrder) {
            List<RenderPass.Draw<GpuBufferSlice[]>> translucent = draws.get(ChunkSectionLayer.TRANSLUCENT);
            java.util.Collections.reverse(translucent);
        }

        GpuBufferSlice terrainUniform = RenderSystem.getDynamicUniforms().writeTerrainTransform(
                modelViewMatrix,
                Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView().getWidth(0),
                Minecraft.getInstance().getTextureManager()
                        .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView().getHeight(0)
        );
        GpuBufferSlice[] sectionUniforms = RenderSystem.getDynamicUniforms().writeChunkSections(
                sectionInfos.toArray(new DynamicGpuData.ChunkSectionInfo[0])
        );

        if (largestDefaultIndexCount > 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
                    .requestIndexCount(largestDefaultIndexCount);
        }

        return new RadeoniumChunkSectionsToRender(
                terrainUniform, largestDefaultIndexCount, draws, sectionUniforms
        );
    }

    @Override
    protected void render(
            ChunkSectionLayer layer,
            RenderPass renderPass,
            GpuBuffer defaultIndexBuffer,
            IndexType defaultIndexType,
            RenderPipeline pipelineOverride,
            RenderPipeline pipelineOverrideMultidraw
    ) {
        RenderPipeline pipeline = pipelineOverride != null ? pipelineOverride : layer.pipeline(false);
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));

        List<RenderPass.Draw<GpuBufferSlice[]>> layerDraws = draws.get(layer);
        if (layerDraws != null && !layerDraws.isEmpty()) {
            renderPass.drawMultipleIndexed(
                    layerDraws,
                    defaultIndexBuffer,
                    defaultIndexType,
                    List.of("ChunkSection"),
                    sectionUniforms
            );
        }
    }
}
