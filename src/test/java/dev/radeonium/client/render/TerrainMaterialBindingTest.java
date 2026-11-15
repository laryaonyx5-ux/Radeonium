package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerrainMaterialBindingTest {
    @Test
    void materialBindingRequiresAValidIndexLayerAndAlphaMode() {
        ChunkSectionLayer layer = ChunkSectionLayer.values()[0];
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainMaterialBinding(-1, layer,
                        TerrainMaterialBinding.AlphaMode.OPAQUE, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainMaterialBinding(0, null,
                        TerrainMaterialBinding.AlphaMode.OPAQUE, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TerrainMaterialBinding(0, layer, null, true, true));
    }

    @Test
    void materialBindingClassifiesMinecraftTerrainLayers() {
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            MinecraftMaterialTable.Material material =
                    new MinecraftMaterialTable.Material(7, layer, null);
            TerrainMaterialBinding binding = TerrainMaterialBinding.from(material);

            assertEquals(7, binding.materialIndex());
            assertEquals(layer, binding.layer());
            assertTrue(binding.useVertexColor());
            assertTrue(binding.useLightmap());

            if (layer.name().equals("TRANSLUCENT")) {
                assertEquals(TerrainMaterialBinding.AlphaMode.BLEND, binding.alphaMode());
            } else if (layer.name().equals("CUTOUT") || layer.name().equals("CUTOUT_MIPPED")) {
                assertEquals(TerrainMaterialBinding.AlphaMode.CUTOUT, binding.alphaMode());
            } else {
                assertEquals(TerrainMaterialBinding.AlphaMode.OPAQUE, binding.alphaMode());
            }
        }
    }

    @Test
    void materialBindingRejectsNullMaterial() {
        assertThrows(IllegalArgumentException.class, () -> TerrainMaterialBinding.from(null));
    }
}
