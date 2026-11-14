package dev.radeonium.client.render;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** CPU-side owner for the 64 section slots in a 4x4x4 render region. */
public final class RenderRegion {
    private final SectionPos.RegionPos position;
    private final Map<Integer, SectionMesh> meshes = new HashMap<>();
    private long totalMeshBytes;

    public RenderRegion(SectionPos.RegionPos position) {
        this.position = position;
    }

    public SectionPos.RegionPos position() { return position; }

    public synchronized void put(int sectionIndex, SectionMesh mesh) {
        checkIndex(sectionIndex);
        SectionMesh previous = mesh == null
                ? meshes.remove(sectionIndex)
                : meshes.put(sectionIndex, mesh);
        if (previous != null) totalMeshBytes -= previous.byteSize();
        if (mesh != null) totalMeshBytes += mesh.byteSize();
    }

    public synchronized SectionMesh remove(int sectionIndex) {
        checkIndex(sectionIndex);
        SectionMesh removed = meshes.remove(sectionIndex);
        if (removed != null) totalMeshBytes -= removed.byteSize();
        return removed;
    }

    public synchronized SectionMesh get(int sectionIndex) {
        checkIndex(sectionIndex);
        return meshes.get(sectionIndex);
    }

    public synchronized Map<Integer, SectionMesh> snapshotMeshes() {
        return Collections.unmodifiableMap(new HashMap<>(meshes));
    }

    public synchronized int meshCount() { return meshes.size(); }
    public synchronized long totalMeshBytes() { return totalMeshBytes; }
    public synchronized boolean isEmpty() { return meshes.isEmpty(); }

    private static void checkIndex(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= 64) {
            throw new IndexOutOfBoundsException("Section index must be in [0, 63]");
        }
    }
}
