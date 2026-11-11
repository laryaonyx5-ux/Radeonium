package dev.radeonium.mixin;

import dev.radeonium.client.chunk.ChunkBuildBudget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooths chunk rebuild spikes by limiting synchronous section compilation.
 *
 * Minecraft's existing async queue, cancellation, ordering and GPU upload
 * pipeline remain responsible for all deferred work.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "compileSections", at = @At("HEAD"))
    private void radeonium$beginChunkBuildFrame(CallbackInfo ci) {
        ChunkBuildBudget.beginFrame(System.nanoTime());
    }

    @Redirect(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
            )
    )
    private void radeonium$budgetSynchronousChunkBuild(
            SectionRenderDispatcher.RenderSection section,
            RenderSectionRegion region
    ) {
        if (ChunkBuildBudget.reserveSynchronousBuild()) {
            section.compileSync(region);
        } else {
            section.compileAsync(region);
        }
    }
}
