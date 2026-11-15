package dev.radeonium.mixin;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public interface LevelRendererAccessor {
    @Accessor("visibleSections")
    List<RenderSection> radeonium$getVisibleSections();

    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher radeonium$getSectionRenderDispatcher();

    @Accessor("optionsRenderState")
    OptionsRenderState radeonium$getOptionsRenderState();
}
