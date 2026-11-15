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

    /** Drops cached section identities when Minecraft unloads the current world. */
    public static void reset() {
        previous.clear();
        working.clear();
        RadeoniumClient.rendererPipeline().clear();
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
            long packed = section.getSectionNode();
            SectionPos position = SectionPos.of(packed);
            dev.radeonium.client.render.SectionPos radeoniumPosition =
                    new dev.radeonium.client.render.SectionPos(position.x(), position.y(), position.z());
            if (mesh == null) {
                RadeoniumClient.rendererPipeline().forgetVanillaMesh(radeoniumPosition);
                continue;
            }

            SectionMesh.SectionDraw solid = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            SectionMesh.SectionDraw cutout = mesh.getSectionDraw(ChunkSectionLayer.CUTOUT);
            SectionMesh.SectionDraw translucent = mesh.getSectionDraw(ChunkSectionLayer.TRANSLUCENT);

            RadeoniumClient.rendererPipeline().observeVanillaMesh(
                    radeoniumPosition,
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


    static List<dev.radeonium.client.render.SectionPos> affectedSectionsForDirty(
            int sectionX, int sectionY, int sectionZ
    ) {
        dev.radeonium.client.render.SectionPos dirty =
                new dev.radeonium.client.render.SectionPos(sectionX, sectionY, sectionZ);
        List<dev.radeonium.client.render.SectionPos> affected = new ArrayList<>(7);
        affected.add(dirty);
        for (SectionFace face : SectionFace.values()) affected.add(dirty.offset(face));
        return List.copyOf(affected);
    }

    public static void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        dev.radeonium.client.render.SectionPos dirty =
                new dev.radeonium.client.render.SectionPos(sectionX, sectionY, sectionZ);
        // A changed section may alter face occlusion in any neighboring section.
        // Invalidate the CPU snapshot as well as its queued GPU upload: otherwise
        // the removal drain would see a still-current snapshot and keep stale GPU data.
        for (dev.radeonium.client.render.SectionPos affected :
                affectedSectionsForDirty(sectionX, sectionY, sectionZ)) {
            RadeoniumClient.rendererPipeline().markDirty(affected);
            net.minecraft.core.SectionPos minecraftPosition =
                    net.minecraft.core.SectionPos.of(affected.x(), affected.y(), affected.z());
            MinecraftSectionModelCaptureStore.remove(minecraftPosition);
            RadeoniumClient.queueSectionModelRemoval(minecraftPosition);
        }
    }
}
