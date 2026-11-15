package dev.radeonium.mixin;

import dev.radeonium.client.chunk.ChunkBuildBudget;
import dev.radeonium.client.render.RadeoniumChunkSectionsToRender;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the terrain frame-graph through Radeonium's own section submission
 * builder while retaining Minecraft's model compilation and buffer allocator.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "endFrame", at = @At("HEAD"))
    private void radeonium$sampleCompletedFrameInterval(CallbackInfo ci) {
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

    @Inject(
            method = "prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void radeonium$buildTerrainDrawList(
            Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder,
            CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        cir.setReturnValue(RadeoniumChunkSectionsToRender.create(
                (LevelRenderer) (Object) this, modelViewMatrix, respectTranslucentOrder
        ));
    }

    @Inject(
            method = "prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void radeonium$replaceIndirectTerrainPreparation(
            Matrix4fc modelViewMatrix,
            boolean respectTranslucentOrder,
            CallbackInfoReturnable<ChunkSectionsToRender> cir
    ) {
        cir.setReturnValue(RadeoniumChunkSectionsToRender.create(
                (LevelRenderer) (Object) this, modelViewMatrix, respectTranslucentOrder
        ));
    }
}
