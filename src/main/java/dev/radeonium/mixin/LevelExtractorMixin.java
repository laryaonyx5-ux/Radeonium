package dev.radeonium.mixin;

import dev.radeonium.client.render.RendererSectionTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the same visible-section and dirty-section lifecycle that vanilla
 * consumes when preparing LevelRenderState. It does not replace vanilla draw
 * submission or alter Minecraft's compiled section meshes.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Shadow @Final private LevelRenderer levelRenderer;

    @Inject(method = "extract", at = @At("HEAD"))
    private void radeonium$captureVisibleSections(CallbackInfo ci) {
        if (this.levelRenderer != null) {
            Iterable<SectionRenderDispatcher.RenderSection> visible =
                    this.levelRenderer.visibleSections();
            RendererSectionTracker.captureVisibleSections(visible);
        }
    }

    @Inject(method = "setSectionDirty", at = @At("HEAD"))
    private void radeonium$trackDirtySection(
            int sectionX, int sectionY, int sectionZ, CallbackInfo ci
    ) {
        RendererSectionTracker.markSectionDirty(sectionX, sectionY, sectionZ);
    }
}
