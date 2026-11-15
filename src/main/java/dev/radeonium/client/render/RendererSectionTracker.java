package dev.radeonium.client.render;

import dev.radeonium.client.RadeoniumClient;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges Minecraft's visible-section lifecycle and the exact compiled
 * section face-connectivity matrix into Radeonium's CPU visibility graph.
 */
public final class RendererSectionTracker {
    private static LongOpenHashSet previous = new LongOpenHashSet();
    private static LongOpenHashSet working = new LongOpenHashSet();
    private static final long ALL_CONNECTIONS = (1L << 36) - 1L;

    private RendererSectionTracker() {}

    public static void reset() {
        previous.clear();
        working.clear();
        RadeoniumClient.rendererPipeline().clear();
    }

    public static void captureVisibleSections(
            Iterable<SectionRenderDispatcher.RenderSection> sections
    ) {
        working.clear();
        for (SectionRenderDispatcher.RenderSection section : sections) working.add(section.getSectionNode());

        if (!working.equals(previous)) {
            List<dev.radeonium.client.render.SectionPos> snapshot = new ArrayList<>(working.size());
            LongIterator iterator = working.iterator();
            while (iterator.hasNext()) {
                SectionPos position = SectionPos.of(iterator.nextLong());
                snapshot.add(new dev.radeonium.client.render.SectionPos(
                        position.x(), position.y(), position.z()));
            }
            RadeoniumClient.rendererPipeline().syncVisibleSections(snapshot);
            LongOpenHashSet oldPrevious = previous;
            previous = working;
            working = oldPrevious;
        }
        working.clear();
    }

    private static Direction direction(SectionFace face) {
        return switch (face) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    /**
     * Minecraft exposes the compiled VisibilitySet through SectionMesh.
     * Unknown/uncompiled meshes are treated as fully connected so missing
     * metadata can never hide terrain.
     */
    private static long visibilityConnections(SectionMesh mesh) {
        if (mesh == null) return ALL_CONNECTIONS;
        long mask = 0L;
        try {
            for (SectionFace entry : SectionFace.values()) {
                for (SectionFace exit : SectionFace.values()) {
                    if (mesh.facesCanSeeEachother(direction(entry), direction(exit))) {
                        mask |= 1L << (entry.ordinal() * 6 + exit.ordinal());
                    }
                }
            }
            return mask;
        } catch (RuntimeException unavailableVisibility) {
            return ALL_CONNECTIONS;
        }
    }

    public static void captureCompiledMeshes(
            Iterable<SectionRenderDispatcher.RenderSection> sections
    ) {
        for (SectionRenderDispatcher.RenderSection section : sections) {
            net.minecraft.client.renderer.chunk.SectionMesh mesh = section.getSectionMesh();
            long packed = section.getSectionNode();
            SectionPos position = SectionPos.of(packed);
            dev.radeonium.client.render.SectionPos radeoniumPosition =
                    new dev.radeonium.client.render.SectionPos(position.x(), position.y(), position.z());

            RadeoniumClient.rendererPipeline().updateVisibilityConnections(
                    radeoniumPosition, visibilityConnections(mesh));

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
