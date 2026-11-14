package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                materialIndex = materials.size();
                indices.put(info, materialIndex);
                materials.add(new Material(materialIndex, quad.layer(), info));
            }
            quadMaterials[quadIndex++] = materialIndex;
        }
        return quadMaterials;
    }

    public synchronized Material get(int index) {
        return materials.get(index);
    }

    public synchronized int size() {
        return materials.size();
    }

    public synchronized List<Material> snapshot() {
        return List.copyOf(materials);
    }

    public synchronized void clear() {
        indices.clear();
        materials.clear();
    }

    /** Material metadata is retained exactly as supplied by Minecraft. */
    public record Material(
            int index,
            ChunkSectionLayer layer,
            BakedQuad.MaterialInfo minecraftInfo
    ) {}
}
