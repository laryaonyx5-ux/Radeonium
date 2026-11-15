package dev.radeonium.client.render;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;

/**
 * Owns layer-separated GPU uploads and material indices produced from baked models.
 *
 * GPU creation and destruction must run on Minecraft's render thread. The
 * section compiler feeds this cache through a bounded render-thread upload
 * queue. Terrain draw submission remains owned by the caller's frame graph.
 */
public final class MinecraftSectionGpuCache implements AutoCloseable {
    private final MinecraftBlockModelMesher mesher = new MinecraftBlockModelMesher();
    private final MinecraftMaterialTable materials = new MinecraftMaterialTable();
    private final MinecraftGpuResourceOwner gpuResources = new MinecraftGpuResourceOwner();
    private static final int MAX_CACHED_SECTIONS = 64;
    private static final long MAX_CACHED_BYTES = 64L * 1024L * 1024L;
    private final Map<SectionPos, Entry> sections = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<SectionPos, Vec3> translucentSortCameras = new java.util.HashMap<>();
    private final Map<SectionPos, List<Integer>> translucentSortOrders = new java.util.HashMap<>();

    /** Builds one GPU upload per vanilla render layer, then atomically replaces the old entry. */
    public synchronized void rebuild(SectionPos position, List<BlockStateModelPart> modelParts) {
        RenderSystem.assertOnRenderThread();
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        if (modelParts == null) throw new IllegalArgumentException("modelParts cannot be null");

        MinecraftBlockModelMesh mesh = mesher.build(modelParts);
        EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads =
                new EnumMap<>(ChunkSectionLayer.class);
        int[] materialIndices;
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                MinecraftBlockModelMesh layerMesh = mesh.forLayer(layer);
                if (layerMesh.quadCount() > 0) {
                    int[] layerMaterialIndices = materials.register(layerMesh);
                    layerUploads.put(layer, gpuResources.upload(layerMesh, layerMaterialIndices));
                }
            }
            materialIndices = materials.register(mesh);
            materials.retain(materialIndices);
        } catch (RuntimeException | Error failure) {
            cleanupFailedBuild(layerUploads, failure);
            throw failure;
        }

        // Commit only after every replacement upload and material reference is ready.
        // A later disposal/eviction failure must not release buffers already installed.
        Entry replacement = new Entry(mesh, MinecraftFluidSectionMesher.SectionFluidMesh.empty(), null,
                Set.of(), layerUploads, materialIndices);
        Entry previous = sections.get(position);
        if (previous != null) {
            try {
                previous.close(materials, gpuResources);
            } catch (RuntimeException | Error failure) {
                cleanupFailedReplacement(layerUploads, materialIndices, failure);
                throw failure;
            }
        }
        sections.put(position, replacement);
        translucentSortCameras.remove(position);
        translucentSortOrders.remove(position);
        evictExcessSections();
    }


    /**
     * Uploads an asynchronously built, layer-separated section result.
     * Call only on the render thread after the CPU snapshot is published.
     */
    public synchronized void rebuild(
            SectionPos position, MinecraftSectionModelMesher.SectionModelMesh sectionMesh
    ) {
        rebuild(position, sectionMesh, null, Set.of());
    }

    /** Installs only layers that passed exact parity against Minecraft's compiled bytes. */
    public synchronized void rebuild(
            SectionPos position,
            MinecraftSectionModelCaptureStore.Snapshot snapshot,
            Set<ChunkSectionLayer> parityVerifiedLayers
    ) {
        if (snapshot == null || parityVerifiedLayers == null) {
            throw new IllegalArgumentException("snapshot and parityVerifiedLayers are required");
        }
        rebuild(position, snapshot.mesh(), snapshot, parityVerifiedLayers);
    }

    private synchronized void rebuild(
            SectionPos position,
            MinecraftSectionModelMesher.SectionModelMesh sectionMesh,
            MinecraftSectionModelCaptureStore.Snapshot sourceSnapshot,
            Set<ChunkSectionLayer> parityVerifiedLayers
    ) {
        RenderSystem.assertOnRenderThread();
        if (position == null || sectionMesh == null || parityVerifiedLayers == null) {
            throw new IllegalArgumentException("position and sectionMesh are required");
        }

        EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads =
                new EnumMap<>(ChunkSectionLayer.class);
        List<MinecraftBlockModelMesh.Quad> allQuads = new ArrayList<>();
        MinecraftBlockModelMesh combined;
        int[] materialIndices;
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                if (!parityVerifiedLayers.contains(layer)) continue;
                MinecraftBlockModelMesh layerMesh = sectionMesh.layer(layer);
                MinecraftFluidSectionMesher.FluidLayerMesh fluidLayer = sectionMesh.fluids(layer);
                if (layerMesh.quadCount() > 0 || !fluidLayer.quads().isEmpty()) {
                    allQuads.addAll(layerMesh.quads());
                    int[] layerMaterialIndices = materials.register(layerMesh);
                    layerUploads.put(layer, gpuResources.upload(
                            com.mojang.blaze3d.systems.RenderSystem.getDevice(),
                            layerMesh, layerMaterialIndices, fluidLayer.quads()));
                }
            }
            combined = new MinecraftBlockModelMesh(allQuads);
            materialIndices = materials.register(combined);
            materials.retain(materialIndices);
        } catch (RuntimeException | Error failure) {
            cleanupFailedBuild(layerUploads, failure);
            throw failure;
        }

        // Keep the old section intact until the complete replacement is prepared.
        Entry replacement = new Entry(combined, sectionMesh.fluidMesh(), sourceSnapshot,
                Set.copyOf(parityVerifiedLayers), layerUploads, materialIndices);
        Entry previous = sections.get(position);
        if (previous != null) {
            try {
                previous.close(materials, gpuResources);
            } catch (RuntimeException | Error failure) {
                cleanupFailedReplacement(layerUploads, materialIndices, failure);
                throw failure;
            }
        }
        sections.put(position, replacement);
        translucentSortCameras.remove(position);
        translucentSortOrders.remove(position);
        evictExcessSections();
    }

    /**
     * Rolls back a fully prepared replacement whose old entry could not be
     * disposed. Its material references were retained before the old entry closed.
     */
    private void cleanupFailedReplacement(
            EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> uploads,
            int[] materialIndices,
            Throwable originalFailure
    ) {
        for (MinecraftMeshGpuUpload upload : uploads.values()) {
            try {
                gpuResources.release(upload);
            } catch (RuntimeException | Error cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            materials.release(materialIndices);
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
        try {
            materials.discardUnreferenced();
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Releases only resources from an uncommitted build. Cleanup failures are
     * attached to the original error so they cannot hide the actual build cause.
     */
    private void cleanupFailedBuild(
            EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> uploads,
            Throwable originalFailure
    ) {
        for (MinecraftMeshGpuUpload upload : uploads.values()) {
            try {
                gpuResources.release(upload);
            } catch (RuntimeException | Error cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        }
        try {
            materials.discardUnreferenced();
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private void evictExcessSections() {
        while (sections.size() > MAX_CACHED_SECTIONS || uploadedBytes() > MAX_CACHED_BYTES) {
            Map.Entry<SectionPos, Entry> oldest = sections.entrySet().iterator().next();
            // Keep the entry reachable until all of its references have been released.
            oldest.getValue().close(materials, gpuResources);
            SectionPos position = oldest.getKey();
            sections.remove(position);
            translucentSortCameras.remove(position);
            translucentSortOrders.remove(position);
        }
    }

    public synchronized void remove(SectionPos position) {
        RenderSystem.assertOnRenderThread();
        Entry entry = sections.get(position);
        if (entry != null) entry.close(materials, gpuResources);
        sections.remove(position);
        translucentSortCameras.remove(position);
        translucentSortOrders.remove(position);
    }

    public synchronized void clear() {
        RenderSystem.assertOnRenderThread();
        RuntimeException failure = null;
        var iterator = sections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SectionPos, Entry> section = iterator.next();
            try {
                section.getValue().close(materials, gpuResources);
                translucentSortCameras.remove(section.getKey());
                translucentSortOrders.remove(section.getKey());
                iterator.remove();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        // Failed entries remain reachable for a later retry. Do not sweep their
        // uploads or clear their material slots while they still reference them.
        if (failure != null) throw failure;
        translucentSortCameras.clear();
        translucentSortOrders.clear();
        // Sweep owner-tracked allocations not associated with a cache entry.
        gpuResources.releaseAll();
        materials.clear();
    }

    public synchronized int sectionCount() { return sections.size(); }
    public synchronized int materialCount() { return materials.size(); }

    public synchronized int uploadedVertexCount() {
        int total = 0;
        for (Entry entry : sections.values()) {
            for (MinecraftMeshGpuUpload upload : entry.layerUploads.values()) {
                total = Math.addExact(total, upload.vertexCount());
            }
        }
        return total;
    }

    /** Counts unique live GPU allocations, not repeated section references. */
    public synchronized long uploadedBytes() {
        return gpuResources.allocatedBytes();
    }

    public synchronized int gpuAllocationCount() {
        return gpuResources.allocationCount();
    }


    /** GPU buffers in the exact native terrain layout, or null for stale/empty sections. */
    public synchronized LayerBuffers layerBuffers(SectionPos position, ChunkSectionLayer layer) {
        return layerBuffers(position, layer, null);
    }

    /**
     * Returns the active layer buffer. Translucent quads are re-sorted by
     * camera distance when the camera has moved enough to change their order.
     */
    public synchronized LayerBuffers layerBuffers(
            SectionPos position, ChunkSectionLayer layer, Vec3 cameraPosition
    ) {
        if (position == null || layer == null) return null;
        Entry entry = sections.get(position);
        if (entry == null) return null;
        if (entry.sourceSnapshot == null
                || MinecraftSectionModelCaptureStore.get(entry.sourceSnapshot.position()) != entry.sourceSnapshot) {
            remove(position);
            return null;
        }
        if (!entry.parityVerified.contains(layer)) return null;

        if (layer == ChunkSectionLayer.TRANSLUCENT && cameraPosition != null) {
            Vec3 previousCamera = translucentSortCameras.get(position);
            if (previousCamera == null || !previousCamera.equals(cameraPosition)) {
                List<SortableQuad> sorted = new ArrayList<>();
                int ordinal = 0;
                for (MinecraftBlockModelMesh.Quad quad : entry.mesh.forLayer(layer).quads()) {
                    MinecraftMeshGpuUpload.QuadData data = MinecraftMeshGpuUpload.fromModelQuad(quad);
                    sorted.add(new SortableQuad(data, distanceSquared(position, data, cameraPosition), ordinal++));
                }
                for (MinecraftFluidSectionMesher.FluidQuad quad : entry.fluids.layer(layer).quads()) {
                    MinecraftMeshGpuUpload.QuadData data = MinecraftMeshGpuUpload.fromFluidQuad(quad);
                    sorted.add(new SortableQuad(data, distanceSquared(position, data, cameraPosition), ordinal++));
                }
                sorted.sort(java.util.Comparator.comparingDouble(SortableQuad::distanceSquared).reversed()
                        .thenComparingInt(SortableQuad::ordinal));
                List<Integer> nextOrder = sorted.stream().map(SortableQuad::ordinal).toList();
                List<Integer> previousOrder = translucentSortOrders.get(position);
                if (!nextOrder.equals(previousOrder)) {
                    List<MinecraftMeshGpuUpload.QuadData> ordered =
                            sorted.stream().map(SortableQuad::quad).toList();
                    MinecraftMeshGpuUpload replacement;
                    try {
                        replacement = gpuResources.uploadQuadData(RenderSystem.getDevice(), ordered);
                    } catch (RuntimeException uploadFailure) {
                        // Keep the previous valid upload (or let the caller use vanilla)
                        // rather than failing the entire frame on a sort-buffer allocation.
                        return null;
                    }
                    MinecraftMeshGpuUpload previous = entry.layerUploads.put(layer, replacement);
                    if (previous != null) gpuResources.release(previous);
                    translucentSortOrders.put(position, nextOrder);
                }
                translucentSortCameras.put(position, cameraPosition);
                if (MinecraftSectionModelCaptureStore.get(entry.sourceSnapshot.position()) != entry.sourceSnapshot) {
                    remove(position);
                    return null;
                }
            }
        }
        MinecraftMeshGpuUpload upload = entry.layerUploads.get(layer);
        if (upload == null || upload.isEmpty()) return null;
        return new LayerBuffers(
                upload.vertexBuffer(), upload.vertexCount(), upload.indexCount(),
                upload.vertexFormat(), upload.topology()
        );
    }


    static List<MinecraftMeshGpuUpload.QuadData> sortTranslucentQuads(
            SectionPos section, List<MinecraftMeshGpuUpload.QuadData> quads, Vec3 camera
    ) {
        if (section == null || quads == null || camera == null) {
            throw new IllegalArgumentException("section, quads and camera are required");
        }
        List<SortableQuad> sorted = new ArrayList<>(quads.size());
        for (MinecraftMeshGpuUpload.QuadData quad : quads) {
            sorted.add(new SortableQuad(quad, distanceSquared(section, quad, camera), sorted.size()));
        }
        sorted.sort(java.util.Comparator.comparingDouble(SortableQuad::distanceSquared).reversed()
                .thenComparingInt(SortableQuad::ordinal));
        return sorted.stream().map(SortableQuad::quad).toList();
    }

    private static double distanceSquared(
            SectionPos section, MinecraftMeshGpuUpload.QuadData quad, Vec3 camera
    ) {
        float[] positions = quad.positions();
        double x = 0.0, y = 0.0, z = 0.0;
        for (int vertex = 0; vertex < 4; vertex++) {
            x += positions[vertex * 3];
            y += positions[vertex * 3 + 1];
            z += positions[vertex * 3 + 2];
        }
        x = section.x() * 16.0 + x * 0.25;
        y = section.y() * 16.0 + y * 0.25;
        z = section.z() * 16.0 + z * 0.25;
        double dx = x - camera.x();
        double dy = y - camera.y();
        double dz = z - camera.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private record SortableQuad(MinecraftMeshGpuUpload.QuadData quad, double distanceSquared, int ordinal) {}

    public record LayerBuffers(
            GpuBuffer vertexBuffer,
            int vertexCount,
            int indexCount,
            String vertexFormat,
            String topology
    ) {}

    /** Draws only the selected layer into a caller-owned RenderPearl pass. */
    public synchronized int drawLayer(
            RenderPass pass, CompiledRenderPipeline pipeline, ChunkSectionLayer layer
    ) {
        RenderSystem.assertOnRenderThread();
        if (pass == null || pipeline == null || layer == null) {
            throw new IllegalArgumentException("pass, pipeline and layer are required");
        }
        int submitted = 0;
        for (Entry entry : sections.values()) {
            if (entry.sourceSnapshot == null
                    || MinecraftSectionModelCaptureStore.get(entry.sourceSnapshot.position()) != entry.sourceSnapshot
                    || !entry.parityVerified.contains(layer)) continue;
            MinecraftMeshGpuUpload upload = entry.layerUploads.get(layer);
            if (upload == null || upload.isEmpty()) continue;
            if (submitted == 0) pass.setPipeline(pipeline);
            upload.drawInto(pass);
            submitted++;
        }
        return submitted;
    }

    /**
     * Compatibility bridge: submits all cached layers using one caller-supplied
     * pipeline. Production integration must prefer drawLayer with a pipeline
     * matching the selected layer's blend/depth state.
     */
    public synchronized int drawAll(RenderPass pass, CompiledRenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        if (pass == null || pipeline == null) throw new IllegalArgumentException("pass and pipeline are required");
        int submitted = 0;
        for (Entry entry : sections.values()) {
            if (entry.sourceSnapshot == null
                    || MinecraftSectionModelCaptureStore.get(entry.sourceSnapshot.position()) != entry.sourceSnapshot) continue;
            for (MinecraftMeshGpuUpload upload : entry.layerUploads.values()) {
                if (upload.isEmpty()) continue;
                if (submitted == 0) pass.setPipeline(pipeline);
                upload.drawInto(pass);
                submitted++;
            }
        }
        return submitted;
    }

    /** Returns the upload for one layer, or null when the section has no such geometry. */
    public synchronized MinecraftMeshGpuUpload upload(
            SectionPos position, ChunkSectionLayer layer
    ) {
        Entry entry = sections.get(position);
        return entry == null ? null : entry.layerUploads.get(layer);
    }

    /** Legacy accessor: returns the first available layer upload. Prefer the layer-aware overload. */
    public synchronized MinecraftMeshGpuUpload upload(SectionPos position) {
        Entry entry = sections.get(position);
        if (entry == null || entry.layerUploads.isEmpty()) return null;
        return entry.layerUploads.values().iterator().next();
    }

    public synchronized MinecraftFluidSectionMesher.SectionFluidMesh fluidMesh(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? MinecraftFluidSectionMesher.SectionFluidMesh.empty() : entry.fluids;
    }

    public synchronized MinecraftBlockModelMesh mesh(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? null : entry.mesh;
    }

    public synchronized int[] materialIndices(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? new int[0] : entry.materialIndices.clone();
    }

    public synchronized MinecraftMaterialTable materialTable() { return materials; }

    @Override
    public void close() {
        clear();
        gpuResources.close();
    }

    private record Entry(
            MinecraftBlockModelMesh mesh,
            MinecraftFluidSectionMesher.SectionFluidMesh fluids,
            MinecraftSectionModelCaptureStore.Snapshot sourceSnapshot,
            Set<ChunkSectionLayer> parityVerified,
            EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads,
            int[] materialIndices
    ) {
        private Entry {
            if (fluids == null || parityVerified == null) throw new IllegalArgumentException("fluids and parity verification cannot be null");
            parityVerified = Set.copyOf(parityVerified);
            layerUploads = new EnumMap<>(layerUploads);
            materialIndices = materialIndices.clone();
        }

        private void close(MinecraftMaterialTable materials, MinecraftGpuResourceOwner gpuResources) {
            for (MinecraftMeshGpuUpload upload : layerUploads.values()) gpuResources.release(upload);
            materials.release(materialIndices);
        }
    }
}
