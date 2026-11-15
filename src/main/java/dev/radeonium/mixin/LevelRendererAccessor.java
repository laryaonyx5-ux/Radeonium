package dev.radeonium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public interface LevelRendererAccessor {
    @Accessor("visibleSections")
    ObjectArrayList<RenderSection> radeonium$getVisibleSections();

    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher radeonium$getSectionRenderDispatcher();

    @Accessor("optionsRenderState")
    OptionsRenderState radeonium$getOptionsRenderState();
}
