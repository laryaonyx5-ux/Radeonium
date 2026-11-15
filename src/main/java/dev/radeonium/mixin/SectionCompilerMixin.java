package dev.radeonium.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.radeonium.client.render.MinecraftCompiledGeometryStore;
import dev.radeonium.client.render.MinecraftSectionModelCaptureStore;
import dev.radeonium.client.render.MinecraftSectionModelMesher;
import dev.radeonium.client.chunk.ChunkBuildBudget;
import java.util.HashMap;
import java.util.Map;
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
 * Optionally builds Radeonium's model mesh from the immutable section region
 * before allowing Minecraft's compiler to run unchanged. The default path
 * still mirrors Minecraft's completed per-layer geometry; vanilla remains
 * authoritative for uploads, special renderers and terrain submission.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow @Final private BlockStateModelSet blockModelSet;

    private static final ThreadLocal<Map<SectionPos, AsyncCapture>> RADEONIUM_CAPTURE =
            ThreadLocal.withInitial(HashMap::new);
    private record AsyncCapture(MinecraftSectionModelCaptureStore.CaptureTicket ticket,
                                MinecraftSectionModelMesher.CapturedSectionInput input) {}

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
        RADEONIUM_CAPTURE.get().remove(sectionPos);
        MinecraftSectionModelCaptureStore.remove(sectionPos);
        if (ChunkBuildBudget.isEnabled() && MinecraftSectionModelCaptureStore.captureEnabled()) {
            var ticket = MinecraftSectionModelCaptureStore.beginAsyncCapture(sectionPos);
            try {
                var input = new MinecraftSectionModelMesher().captureInputs(sectionPos, region, blockModelSet);
                RADEONIUM_CAPTURE.get().put(sectionPos, new AsyncCapture(ticket, input));
                if (RADEONIUM_CAPTURE.get().size() > 32) RADEONIUM_CAPTURE.get().clear();
            } catch (RuntimeException exception) {
                MinecraftSectionModelCaptureStore.abortAsync(ticket);
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
        AsyncCapture capture = RADEONIUM_CAPTURE.get().remove(sectionPos);
        if (capture != null) {
            dev.radeonium.client.RadeoniumClient.sectionMeshingExecutor()
                    .buildMinecraftAsync(capture.input())
                    .whenComplete((mesh, error) -> {
                        if (error != null) MinecraftSectionModelCaptureStore.abortAsync(capture.ticket());
                        else MinecraftSectionModelCaptureStore.publishAsync(capture.ticket(), mesh);
                    });
        }
        MinecraftCompiledGeometryStore.Snapshot snapshot =
                MinecraftCompiledGeometryStore.captureCurrent(sectionPos, cir.getReturnValue());
        if (snapshot != null) {
            // SectionCompiler runs on workers; the client tick drains this queue on the render thread.
            dev.radeonium.client.RadeoniumClient.queueCompiledGeometryUpload(snapshot);
        }
    }
}
