package dev.radeonium.mixin;

import dev.radeonium.client.chunk.ChunkBuildBudget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapts render-thread chunk rebuild work while preserving Minecraft's own
 * asynchronous task queue, cancellation, ordering and GPU upload pipeline.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "compileSections", at = @At("HEAD"))
    private void radeonium$beginChunkBuildFrame(CallbackInfo ci) {
        ChunkBuildBudget.beginFrame(System.nanoTime());
    }

    @Inject(
            method = "scheduleTranslucentSectionResort",
            at = @At("HEAD"),
            cancellable = true
    )
    private void radeonium$deferTranslucencyResortDuringSlowFrames(
            Vec3 cameraPos,
            CallbackInfo ci
    ) {
        if (ChunkBuildBudget.shouldDeferTranslucencyResort()) {
            ci.cancel();
        }
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
        if (!ChunkBuildBudget.reserveSynchronousBuild()) {
            section.compileAsync(region);
            return;
        }

        long startedAt = System.nanoTime();
        try {
            section.compileSync(region);
        } finally {
            ChunkBuildBudget.recordSynchronousBuild(System.nanoTime() - startedAt);
        }
    }
}
