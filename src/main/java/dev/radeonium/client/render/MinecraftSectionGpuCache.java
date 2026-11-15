package dev.radeonium.client.render;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_CACHED_SECTIONS = 64;
    private static final long MAX_CACHED_BYTES = 64L * 1024L * 1024L;
    private final Map<SectionPos, Entry> sections = new LinkedHashMap<>(16, 0.75f, true);

    /** Builds one GPU upload per vanilla render layer, then atomically replaces the old entry. */
    public synchronized void rebuild(SectionPos position, List<BlockStateModelPart> modelParts) {
        RenderSystem.assertOnRenderThread();
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        if (modelParts == null) throw new IllegalArgumentException("modelParts cannot be null");

        MinecraftBlockModelMesh mesh = mesher.build(modelParts);
        EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads =
                new EnumMap<>(ChunkSectionLayer.class);
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                MinecraftBlockModelMesh layerMesh = mesh.forLayer(layer);
                if (layerMesh.quadCount() > 0) {
                    int[] layerMaterialIndices = materials.register(layerMesh);
                    layerUploads.put(layer, MinecraftMeshGpuUpload.upload(layerMesh, layerMaterialIndices));
                }
            }
            int[] materialIndices = materials.register(mesh);
            materials.retain(materialIndices);
            Entry replacement = new Entry(mesh, MinecraftFluidSectionMesher.SectionFluidMesh.empty(), layerUploads, materialIndices);
            Entry previous = sections.put(position, replacement);
            if (previous != null) previous.close(materials);
            evictExcessSections();
        } catch (RuntimeException | Error failure) {
            for (MinecraftMeshGpuUpload upload : layerUploads.values()) upload.close();
            materials.discardUnreferenced();
            throw failure;
        }
    }


    /**
     * Uploads an asynchronously built, layer-separated section result.
     * Call only on the render thread after the CPU snapshot is published.
     */
    public synchronized void rebuild(
            SectionPos position, MinecraftSectionModelMesher.SectionModelMesh sectionMesh
    ) {
        RenderSystem.assertOnRenderThread();
        if (position == null || sectionMesh == null) {
            throw new IllegalArgumentException("position and sectionMesh are required");
        }

        EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads =
                new EnumMap<>(ChunkSectionLayer.class);
        List<MinecraftBlockModelMesh.Quad> allQuads = new ArrayList<>();
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                MinecraftBlockModelMesh layerMesh = sectionMesh.layer(layer);
                MinecraftFluidSectionMesher.FluidLayerMesh fluidLayer = sectionMesh.fluids(layer);
                if (layerMesh.quadCount() > 0 || !fluidLayer.quads().isEmpty()) {
                    allQuads.addAll(layerMesh.quads());
                    int[] layerMaterialIndices = materials.register(layerMesh);
                    layerUploads.put(layer, MinecraftMeshGpuUpload.upload(
                            com.mojang.blaze3d.systems.RenderSystem.getDevice(),
                            layerMesh, layerMaterialIndices, fluidLayer.quads()));
                }
            }
            MinecraftBlockModelMesh combined = new MinecraftBlockModelMesh(allQuads);
            int[] materialIndices = materials.register(combined);
            materials.retain(materialIndices);
            Entry replacement = new Entry(combined, sectionMesh.fluidMesh(), layerUploads, materialIndices);
            Entry previous = sections.put(position, replacement);
            if (previous != null) previous.close(materials);
            evictExcessSections();
        } catch (RuntimeException | Error failure) {
            for (MinecraftMeshGpuUpload upload : layerUploads.values()) upload.close();
            materials.discardUnreferenced();
            throw failure;
        }
    }

    private void evictExcessSections() {
        while (sections.size() > MAX_CACHED_SECTIONS || uploadedBytes() > MAX_CACHED_BYTES) {
            SectionPos oldest = sections.keySet().iterator().next();
            Entry removed = sections.remove(oldest);
            if (removed != null) removed.close(materials);
        }
    }

    public synchronized void remove(SectionPos position) {
        RenderSystem.assertOnRenderThread();
        Entry removed = sections.remove(position);
        if (removed != null) removed.close(materials);
    }

    public synchronized void clear() {
        RenderSystem.assertOnRenderThread();
        for (Entry entry : sections.values()) entry.close(materials);
        sections.clear();
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

    public synchronized long uploadedBytes() {
        long total = 0L;
        for (Entry entry : sections.values()) {
            for (MinecraftMeshGpuUpload upload : entry.layerUploads.values()) {
                total = Math.addExact(total, upload.byteSize());
            }
        }
        return total;
    }

    /** Draws only the selected layer into a caller-owned RenderPearl pass. */
    public synchronized int drawLayer(
            RenderPass pass, CompiledRenderPipeline pipeline, ChunkSectionLayer layer
    ) {
        if (pass == null || pipeline == null || layer == null) {
            throw new IllegalArgumentException("pass, pipeline and layer are required");
        }
        int submitted = 0;
        for (Entry entry : sections.values()) {
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
        if (pass == null || pipeline == null) throw new IllegalArgumentException("pass and pipeline are required");
        int submitted = 0;
        for (Entry entry : sections.values()) {
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
    public void close() { clear(); }

    private record Entry(
            MinecraftBlockModelMesh mesh,
            MinecraftFluidSectionMesher.SectionFluidMesh fluids,
            EnumMap<ChunkSectionLayer, MinecraftMeshGpuUpload> layerUploads,
            int[] materialIndices
    ) {
        private Entry {
            if (fluids == null) throw new IllegalArgumentException("fluids cannot be null");
            layerUploads = new EnumMap<>(layerUploads);
            materialIndices = materialIndices.clone();
        }

        private void close(MinecraftMaterialTable materials) {
            for (MinecraftMeshGpuUpload upload : layerUploads.values()) upload.close();
            materials.release(materialIndices);
        }
    }
}
