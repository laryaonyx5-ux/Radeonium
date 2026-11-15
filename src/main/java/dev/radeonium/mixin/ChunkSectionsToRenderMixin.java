package dev.radeonium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.radeonium.client.render.RadeoniumTerrainPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

/**
 * Radeonium-owned implementation of the per-layer terrain draw submission.
 * The existing Minecraft frame graph still owns pass scheduling and OIT,
 * while Radeonium binds the layer pipeline and submits the prepared section draws.
 */
@Mixin(ChunkSectionsToRender.DrawSeparate.class)
public abstract class ChunkSectionsToRenderMixin {
    @Shadow @Final
    private Map<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer;

    @Shadow @Final
    private GpuBufferSlice[] chunkSectionInfos;

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void radeonium$submitTerrainLayer(
            ChunkSectionLayer layer,
            RenderPass renderPass,
            GpuBuffer defaultIndexBuffer,
            IndexType defaultIndexType,
            RenderPipeline pipelineOverride,
            RenderPipeline pipelineOverrideMultidraw,
            CallbackInfo ci
    ) {
        RenderPipeline pipeline = RadeoniumTerrainPipelines.resolve(layer, pipelineOverride);
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));

        List<RenderPass.Draw<GpuBufferSlice[]>> layerDraws = drawsPerLayer.get(layer);
        if (layerDraws != null && !layerDraws.isEmpty()) {
            renderPass.drawMultipleIndexed(
                    layerDraws,
                    defaultIndexBuffer,
                    defaultIndexType,
                    List.of("ChunkSection"),
                    chunkSectionInfos
            );
        }
        ci.cancel();
    }
}
