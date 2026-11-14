package dev.radeonium.client.render;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns GPU uploads and material indices produced from Minecraft baked models.
 *
 * All methods that create or close uploads must be called on Minecraft's
 * render thread. Model extraction can be prepared elsewhere, but RenderPearl
 * buffer creation and destruction belong to the active GPU device thread.
 *
 * This cache is an integration boundary for section compilation. It does not
 * discover model parts from a LevelChunkSection or replace vanilla rendering.
 */
public final class MinecraftSectionGpuCache implements AutoCloseable {
    private final MinecraftBlockModelMesher mesher = new MinecraftBlockModelMesher();
    private final MinecraftMaterialTable materials = new MinecraftMaterialTable();
    private final Map<SectionPos, Entry> sections = new HashMap<>();

    /**
     * Builds baked-model geometry, uploads it and assigns a material index to
     * each quad. The previous upload is released only after the new upload
     * succeeds.
     */
    public synchronized void rebuild(
            SectionPos position,
            List<BlockStateModelPart> modelParts
    ) {
        if (position == null) throw new IllegalArgumentException("position cannot be null");
        if (modelParts == null) throw new IllegalArgumentException("modelParts cannot be null");

        MinecraftBlockModelMesh mesh = mesher.build(modelParts);
        MinecraftMeshGpuUpload upload = MinecraftMeshGpuUpload.upload(mesh);
        int[] materialIndices = materials.register(mesh);
        Entry previous = sections.put(position, new Entry(mesh, upload, materialIndices));
        if (previous != null) previous.close();
    }

    /** Releases the GPU buffer associated with a section, if present. */
    public synchronized void remove(SectionPos position) {
        Entry removed = sections.remove(position);
        if (removed != null) removed.close();
    }

    /** Releases every cached section upload and resets material indexing. */
    public synchronized void clear() {
        for (Entry entry : sections.values()) entry.close();
        sections.clear();
        materials.clear();
    }

    public synchronized int sectionCount() {
        return sections.size();
    }

    public synchronized int materialCount() {
        return materials.size();
    }

    public synchronized int uploadedVertexCount() {
        int total = 0;
        for (Entry entry : sections.values()) {
            total = Math.addExact(total, entry.upload.vertexCount());
        }
        return total;
    }

    public synchronized long uploadedBytes() {
        long total = 0L;
        for (Entry entry : sections.values()) {
            total = Math.addExact(total, entry.upload.byteSize());
        }
        return total;
    }

    public synchronized MinecraftMeshGpuUpload upload(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? null : entry.upload;
    }

    public synchronized MinecraftBlockModelMesh mesh(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? null : entry.mesh;
    }

    /** Returns one material-table index per quad, in mesh order. */
    public synchronized int[] materialIndices(SectionPos position) {
        Entry entry = sections.get(position);
        return entry == null ? new int[0] : entry.materialIndices.clone();
    }

    public synchronized MinecraftMaterialTable materialTable() {
        return materials;
    }

    @Override
    public void close() {
        clear();
    }

    private record Entry(
            MinecraftBlockModelMesh mesh,
            MinecraftMeshGpuUpload upload,
            int[] materialIndices
    ) {
        private Entry {
            materialIndices = materialIndices.clone();
        }

        private void close() {
            upload.close();
        }
    }
}
