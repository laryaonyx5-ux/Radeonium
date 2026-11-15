package dev.radeonium.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.radeonium.client.render.MinecraftCompiledGeometryStore;
import dev.radeonium.client.render.MinecraftSectionModelCaptureStore;
import dev.radeonium.client.render.MinecraftSectionModelMesher;
import dev.radeonium.client.chunk.ChunkBuildBudget;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Copies Minecraft's completed per-layer section geometry before vanilla
 * consumes/releases the MeshData. The original result and vanilla renderer are
 * left untouched.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow @Final private BlockStateModelSet blockModelSet;

    @Shadow @Final private boolean ambientOcclusion;
    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("HEAD")
    )
    private void radeonium$beginCompiledGeometryCapture(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        MinecraftCompiledGeometryStore.beginCapture(sectionPos);
        if (ChunkBuildBudget.isEnabled()) {
            try {
                var mesh = new MinecraftSectionModelMesher()
                        .captureAndBuild(sectionPos, region, blockModelSet);
                MinecraftSectionModelCaptureStore.put(sectionPos, mesh);
            } catch (RuntimeException exception) {
                // Unsupported modded model behavior must never break vanilla compilation.
                MinecraftSectionModelCaptureStore.remove(sectionPos);
            }
        }
    }

    @Inject(
            method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
            at = @At("RETURN")
    )
    private void radeonium$captureCompiledGeometry(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        MinecraftCompiledGeometryStore.Snapshot snapshot =
                MinecraftCompiledGeometryStore.captureCurrent(sectionPos, cir.getReturnValue());
        if (snapshot != null) {
            // SectionCompiler runs on workers; the client tick drains this queue on the render thread.
            dev.radeonium.client.RadeoniumClient.queueCompiledGeometryUpload(snapshot);
        }
    }
}
