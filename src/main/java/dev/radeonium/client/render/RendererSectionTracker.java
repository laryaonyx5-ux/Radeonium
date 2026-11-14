package dev.radeonium.client.render;

import dev.radeonium.client.RadeoniumClient;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges Minecraft's live visible-section set into Radeonium's CPU pipeline.
 *
 * The primitive packed-node sets are reused between frames. SectionPos objects
 * are materialized only when the visible set changes, not for every section on
 * every frame.
 */
public final class RendererSectionTracker {
    private static LongOpenHashSet previous = new LongOpenHashSet();
    private static LongOpenHashSet working = new LongOpenHashSet();

    private RendererSectionTracker() {
    }

    public static void captureVisibleSections(
            Iterable<SectionRenderDispatcher.RenderSection> sections
    ) {
        working.clear();
        for (SectionRenderDispatcher.RenderSection section : sections) {
            working.add(section.getSectionNode());
        }

        if (!working.equals(previous)) {
            List<dev.radeonium.client.render.SectionPos> snapshot =
                    new ArrayList<>(working.size());
            LongIterator iterator = working.iterator();
            while (iterator.hasNext()) {
                SectionPos position = SectionPos.of(iterator.nextLong());
                snapshot.add(new dev.radeonium.client.render.SectionPos(
                        position.x(), position.y(), position.z()
                ));
            }

            RadeoniumClient.rendererPipeline().syncVisibleSections(snapshot);

            LongOpenHashSet oldPrevious = previous;
            previous = working;
            working = oldPrevious;
        }

        working.clear();
    }

    /**
     * Copies stable metadata from Minecraft's compiled mesh after extraction.
     * The SectionMesh and its GPU resources remain owned by vanilla.
     */
    public static void captureCompiledMeshes(
            Iterable<SectionRenderDispatcher.RenderSection> sections
    ) {
        for (SectionRenderDispatcher.RenderSection section : sections) {
            net.minecraft.client.renderer.chunk.SectionMesh mesh = section.getSectionMesh();
            if (mesh == null) continue;

            SectionMesh.SectionDraw solid = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            SectionMesh.SectionDraw cutout = mesh.getSectionDraw(ChunkSectionLayer.CUTOUT);
            SectionMesh.SectionDraw translucent = mesh.getSectionDraw(ChunkSectionLayer.TRANSLUCENT);

            long packed = section.getSectionNode();
            SectionPos position = SectionPos.of(packed);
            RadeoniumClient.rendererPipeline().observeVanillaMesh(
                    new dev.radeonium.client.render.SectionPos(position.x(), position.y(), position.z()),
                    new VanillaSectionMeshSnapshot(
                            solid == null ? 0 : solid.indexCount(),
                            cutout == null ? 0 : cutout.indexCount(),
                            translucent == null ? 0 : translucent.indexCount(),
                            mesh.hasTranslucentGeometry(),
                            mesh.getRenderableBlockEntities().size(),
                            mesh.getCompileTaskStartTime()
                    )
            );
        }
    }

    public static void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        RadeoniumClient.rendererPipeline().markDirty(
                new dev.radeonium.client.render.SectionPos(sectionX, sectionY, sectionZ)
        );
    }
}
