package dev.radeonium.client.render.contract;

import dev.radeonium.client.render.SectionPos;

import java.util.List;
import java.util.Objects;

/**
 * Backend-neutral API boundary for the future Radeonium-owned terrain renderer.
 *
 * This contract deliberately contains no Minecraft or RenderPearl objects.
 * Adapters at the game boundary translate Minecraft state into immutable
 * snapshots; backend implementations translate opaque handles into GPU work.
 */
public final class RendererContract {
    private RendererContract() {}

    public enum TerrainLayer {
        SOLID,
        CUTOUT,
        CUTOUT_MIPPED,
        TRANSLUCENT
    }

    public enum FallbackReason {
        RENDERER_NOT_READY,
        UNSUPPORTED_MODEL_OR_MATERIAL,
        UNSUPPORTED_GPU_FEATURE,
        MESH_NOT_AVAILABLE,
        STALE_SECTION_GENERATION,
        RESOURCE_RELOAD,
        INTERNAL_VALIDATION_FAILURE
    }

    /** Identifies one specific build attempt in one world lifetime. */
    public record SectionBuildTicket(SectionPos section, long worldEpoch, long generation) {
        public SectionBuildTicket {
            Objects.requireNonNull(section, "section");
            if (worldEpoch < 0 || generation < 0) {
                throw new IllegalArgumentException("World epoch and generation must be non-negative");
            }
        }
    }

    /**
     * Immutable input captured from the game thread. Implementations must not
     * expose live Level, BlockState, WorldChunk or mutable game-owned objects.
     */
    public interface SectionSnapshot {
        SectionBuildTicket ticket();
    }

    /**
     * Immutable renderer-owned CPU mesh. Its vertex layout is identified by a
     * stable renderer format ID; byte contents are defensively copied.
     */
    public record MeshPayload(
            SectionBuildTicket ticket,
            TerrainLayer layer,
            String vertexFormatId,
            byte[] vertexBytes,
            int vertexStride,
            int vertexCount,
            byte[] indexBytes,
            int indexElementBytes,
            int indexCount
    ) {
        public MeshPayload {
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(vertexFormatId, "vertexFormatId");
            Objects.requireNonNull(vertexBytes, "vertexBytes");
            Objects.requireNonNull(indexBytes, "indexBytes");
            if (vertexFormatId.isBlank()) throw new IllegalArgumentException("vertexFormatId cannot be blank");
            if (vertexStride <= 0 || vertexCount < 0 || indexCount < 0) {
                throw new IllegalArgumentException("Invalid mesh counts or stride");
            }
            if (indexElementBytes != 0 && indexElementBytes != 2 && indexElementBytes != 4) {
                throw new IllegalArgumentException("Index elements must be 2 or 4 bytes, or zero when unused");
            }
            if ((long) vertexStride * vertexCount != vertexBytes.length) {
                throw new IllegalArgumentException("Vertex byte length does not match stride and count");
            }
            if ((long) indexElementBytes * indexCount != indexBytes.length) {
                throw new IllegalArgumentException("Index byte length does not match element size and count");
            }
            if ((indexCount == 0) != (indexElementBytes == 0)) {
                throw new IllegalArgumentException("Index metadata must be consistently empty or populated");
            }
            vertexBytes = vertexBytes.clone();
            indexBytes = indexBytes.clone();
        }

        @Override public byte[] vertexBytes() { return vertexBytes.clone(); }
        @Override public byte[] indexBytes() { return indexBytes.clone(); }

        public long byteSize() {
            return (long) vertexBytes.length + indexBytes.length;
        }
    }

    /** Opaque handle owned and released by the Radeonium GPU resource backend. */
    public record GpuMeshHandle(
            long id,
            SectionBuildTicket ticket,
            TerrainLayer layer,
            long byteSize
    ) {
        public GpuMeshHandle {
            if (id <= 0 || byteSize < 0) throw new IllegalArgumentException("Invalid GPU mesh handle");
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(layer, "layer");
        }
    }

    public record FrameContext(long frameId, long worldEpoch) {
        public FrameContext {
            if (frameId < 0 || worldEpoch < 0) {
                throw new IllegalArgumentException("Frame ID and world epoch must be non-negative");
            }
        }
    }

    public record TerrainDraw(GpuMeshHandle mesh, int indexCount) {
        public TerrainDraw {
            Objects.requireNonNull(mesh, "mesh");
            if (indexCount < 0) throw new IllegalArgumentException("indexCount cannot be negative");
            if (indexCount > 0 && mesh.byteSize() == 0) {
                throw new IllegalArgumentException("An indexed draw cannot reference an empty GPU mesh");
            }
        }
    }

    /** CPU-only compilation contract; implementations may run on meshing workers. */
    @FunctionalInterface
    public interface SectionMesher {
        MeshPayload build(SectionSnapshot snapshot);
    }

    /** Owns Radeonium GPU allocations; every method must run on the render thread. */
    public interface GpuResourceOwner extends AutoCloseable {
        GpuMeshHandle upload(MeshPayload mesh);
        void release(GpuMeshHandle mesh);
        @Override void close();
    }

    /** Submits Radeonium-owned draws into a caller-provided terrain pass. */
    @FunctionalInterface
    public interface TerrainPassSubmitter {
        /** Must be called on the render thread inside the host-provided terrain pass. */
        void submit(FrameContext frame, TerrainLayer layer, List<TerrainDraw> draws);
    }

    /**
     * Explicit per-section/per-layer hand-off to Minecraft when Radeonium
     * cannot safely draw its own mesh. The adapter must avoid duplicate draws.
     */
    @FunctionalInterface
    public interface VanillaFallback {
        void renderVanilla(SectionPos section, TerrainLayer layer, FallbackReason reason);
    }

    /**
     * Validates that a worker result belongs to the exact immutable input
     * snapshot that initiated the build; stale generations must be discarded.
     */
    public static MeshPayload requireMatchingResult(SectionSnapshot snapshot, MeshPayload result) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(result, "result");
        if (!snapshot.ticket().equals(result.ticket())) {
            throw new IllegalArgumentException("Mesh result does not match its source section build ticket");
        }
        return result;
    }

    /** Top-level lifecycle contract; Minecraft integration remains an adapter. */
    public interface TerrainRenderer extends AutoCloseable {
        boolean isReady();
        void invalidateSection(SectionPos section, long newGeneration);
        void renderLayer(FrameContext frame, TerrainLayer layer);
        @Override void close();
    }
}
