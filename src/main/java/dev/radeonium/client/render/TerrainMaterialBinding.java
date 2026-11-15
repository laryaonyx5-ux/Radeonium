package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Immutable CPU-side material binding plan for one retained Minecraft
 * material. The original MaterialInfo remains in MinecraftMaterialTable;
 * this record describes how the Radeonium terrain shader consumes the
 * already-resolved vertex attributes without inventing texture coordinates.
 */
public record TerrainMaterialBinding(
        int materialIndex,
        ChunkSectionLayer layer,
        AlphaMode alphaMode,
        boolean useVertexColor,
        boolean useLightmap
) {
    public TerrainMaterialBinding {
        if (materialIndex < 0 || layer == null || alphaMode == null) {
            throw new IllegalArgumentException("material index, layer and alpha mode are required");
        }
    }

    public static TerrainMaterialBinding from(MinecraftMaterialTable.Material material) {
        if (material == null) throw new IllegalArgumentException("material cannot be null");
        String layerName = material.layer().name();
        AlphaMode alphaMode = switch (layerName) {
            case "CUTOUT", "CUTOUT_MIPPED" -> AlphaMode.CUTOUT;
            case "TRANSLUCENT" -> AlphaMode.BLEND;
            default -> AlphaMode.OPAQUE;
        };
        return new TerrainMaterialBinding(
                material.index(), material.layer(), alphaMode, true, true
        );
    }

    public enum AlphaMode {
        OPAQUE,
        CUTOUT,
        BLEND
    }
}
