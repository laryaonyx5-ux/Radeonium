package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds a renderer-owned material index from Minecraft's baked-quad metadata.
 *
 * The table deliberately retains Minecraft's MaterialInfo instead of
 * fabricating atlas coordinates or texture handles. A later RenderPearl
 * pipeline can bind the corresponding atlas and translate these entries into
 * GPU material records.
 */
public final class MinecraftMaterialTable {
    private final Map<BakedQuad.MaterialInfo, Integer> indices = new HashMap<>();
    private final List<Material> materials = new ArrayList<>();
    private final List<Integer> referenceCounts = new ArrayList<>();
    private final java.util.ArrayDeque<Integer> freeIndices = new java.util.ArrayDeque<>();

    /**
     * Registers every distinct material used by a mesh and returns one
     * material index per quad, in the same order as mesh.quads().
     */
    public synchronized int[] register(MinecraftBlockModelMesh mesh) {
        if (mesh == null) throw new IllegalArgumentException("mesh cannot be null");

        int[] quadMaterials = new int[mesh.quadCount()];
        int quadIndex = 0;
        for (MinecraftBlockModelMesh.Quad quad : mesh.quads()) {
            BakedQuad.MaterialInfo info = quad.material();
            Integer materialIndex = indices.get(info);
            if (materialIndex == null) {
                materialIndex = freeIndices.isEmpty() ? materials.size() : freeIndices.removeFirst();
                indices.put(info, materialIndex);
                if (materialIndex == materials.size()) {
                    materials.add(new Material(materialIndex, quad.layer(), info));
                    referenceCounts.add(0);
                } else {
                    materials.set(materialIndex, new Material(materialIndex, quad.layer(), info));
                    referenceCounts.set(materialIndex, 0);
                }
            }
            quadMaterials[quadIndex++] = materialIndex;
        }
        return quadMaterials;
    }


    /**
     * Retains the distinct material slots referenced by one cached section.
     * Call once per section entry, not once per layer upload.
     */
    public synchronized void retain(int[] materialIndices) {
        if (materialIndices == null) throw new IllegalArgumentException("materialIndices cannot be null");
        Set<Integer> unique = distinct(materialIndices);
        Map<Integer, Integer> nextCounts = new HashMap<>();
        // Validate and calculate every transition before mutating the table.
        for (int index : unique) {
            if (index < 0 || index >= materials.size() || materials.get(index) == null) {
                throw new IllegalArgumentException("Unknown material index: " + index);
            }
            nextCounts.put(index, Math.addExact(referenceCounts.get(index), 1));
        }
        nextCounts.forEach(referenceCounts::set);
    }

    /** Releases one section's material references and recycles now-unused slots. */
    public synchronized void release(int[] materialIndices) {
        if (materialIndices == null) throw new IllegalArgumentException("materialIndices cannot be null");
        Set<Integer> unique = distinct(materialIndices);
        Map<Integer, Integer> nextCounts = new HashMap<>();
        // Check all decrements first so an invalid slot cannot partially release a section.
        for (int index : unique) {
            if (index < 0 || index >= materials.size() || materials.get(index) == null) continue;
            int references = referenceCounts.get(index);
            if (references <= 0) throw new IllegalStateException("Material reference underflow: " + index);
            nextCounts.put(index, references - 1);
        }
        for (Map.Entry<Integer, Integer> entry : nextCounts.entrySet()) {
            int index = entry.getKey();
            int references = entry.getValue();
            if (references == 0) {
                Material removed = materials.set(index, null);
                indices.remove(removed.minecraftInfo());
                referenceCounts.set(index, 0);
                freeIndices.addLast(index);
            } else {
                referenceCounts.set(index, references);
            }
        }
    }

    private static Set<Integer> distinct(int[] values) {
        Set<Integer> result = new HashSet<>();
        for (int value : values) if (value >= 0) result.add(value);
        return result;
    }


    /** Recycles newly registered materials that never made it into a section entry. */
    public synchronized void discardUnreferenced() {
        for (int index = 0; index < materials.size(); index++) {
            Material material = materials.get(index);
            if (material != null && referenceCounts.get(index) == 0) {
                indices.remove(material.minecraftInfo());
                materials.set(index, null);
                freeIndices.addLast(index);
            }
        }
    }

    public synchronized int activeSize() {
        int count = 0;
        for (Material material : materials) if (material != null) count++;
        return count;
    }

    public synchronized Material get(int index) {
        return materials.get(index);
    }

    public synchronized int size() {
        return activeSize();
    }

    public synchronized List<Material> snapshot() {
        return materials.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Immutable shader-facing binding plans for every currently live material. */
    public synchronized List<TerrainMaterialBinding> shaderBindings() {
        return materials.stream()
                .filter(java.util.Objects::nonNull)
                .map(TerrainMaterialBinding::from)
                .toList();
    }

    public synchronized void clear() {
        indices.clear();
        materials.clear();
        referenceCounts.clear();
        freeIndices.clear();
    }

    /** Material metadata is retained exactly as supplied by Minecraft. */
    public record Material(
            int index,
            ChunkSectionLayer layer,
            BakedQuad.MaterialInfo minecraftInfo
    ) {}
}
