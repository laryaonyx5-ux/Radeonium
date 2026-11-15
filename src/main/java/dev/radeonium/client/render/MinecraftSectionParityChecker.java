package dev.radeonium.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Compares Radeonium's CPU layer mesh with the bytes emitted by Minecraft's
 * own SectionCompiler. A layer is eligible for Radeonium draw submission only
 * when its native BLOCK-format quad records match exactly.
 *
 * Quad order between sections is intentionally ignored here; order inside each
 * quad is retained, so winding, positions, UVs, tint, AO and packed light must
 * all match. Translucent quad order is sorted separately at draw time.
 */
public final class MinecraftSectionParityChecker {
    private MinecraftSectionParityChecker() {}

    public static ParityReport compare(
            MinecraftSectionModelMesher.SectionModelMesh model,
            MinecraftCompiledGeometryStore.Snapshot vanilla
    ) {
        if (model == null) throw new IllegalArgumentException("model cannot be null");
        EnumMap<ChunkSectionLayer, LayerParity> results =
                new EnumMap<>(ChunkSectionLayer.class);

        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            MinecraftMeshGpuUpload.EncodedLayer encoded = MinecraftMeshGpuUpload.encodeLayer(
                    model.layer(layer), model.fluids(layer).quads());
            MinecraftCompiledGeometryStore.LayerData vanillaLayer =
                    vanilla == null ? null : vanilla.layers().get(layer);

            if (vanillaLayer == null) {
                boolean empty = encoded.vertexCount() == 0;
                results.put(layer, new LayerParity(
                        layer, empty, encoded.vertexCount(), 0,
                        empty ? "both layers are empty" : "Minecraft emitted no layer geometry"
                ));
                continue;
            }

            if (!DefaultVertexFormat.BLOCK.toString().equals(vanillaLayer.vertexFormat())
                    || !com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS
                            .toString().equals(vanillaLayer.topology())) {
                results.put(layer, new LayerParity(
                        layer, false, encoded.vertexCount(), vanillaLayer.vertexCount(),
                        "Minecraft layer uses a different vertex format or topology"
                ));
                continue;
            }

            int expectedBytes = Math.multiplyExact(encoded.vertexCount(), MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES);
            byte[] expected = encoded.vertexBytes();
            byte[] actual = vanillaLayer.vertexBytes();
            boolean validCounts = encoded.vertexCount() == vanillaLayer.vertexCount()
                    && expectedBytes == expected.length
                    && actual.length >= expectedBytes
                    && (encoded.vertexCount() % 4) == 0;
            boolean matches = validCounts && quadMultiset(expected).equals(
                    quadMultiset(Arrays.copyOf(actual, expectedBytes)));
            results.put(layer, new LayerParity(
                    layer, matches, encoded.vertexCount(), vanillaLayer.vertexCount(),
                    matches ? "vertex records match" : "quad vertex records differ"
            ));
        }
        return new ParityReport(results);
    }

    private static Map<String, Integer> quadMultiset(byte[] bytes) {
        int quadBytes = MinecraftMeshGpuUpload.VERTEX_STRIDE_BYTES * 4;
        if (bytes.length % quadBytes != 0) return Map.of();
        Map<String, Integer> counts = new HashMap<>();
        for (int offset = 0; offset < bytes.length; offset += quadBytes) {
            String key = java.util.HexFormat.of().formatHex(bytes, offset, offset + quadBytes);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    public record LayerParity(
            ChunkSectionLayer layer,
            boolean matches,
            int radeoniumVertexCount,
            int vanillaVertexCount,
            String reason
    ) {}

    public record ParityReport(Map<ChunkSectionLayer, LayerParity> layers) {
        public ParityReport {
            if (layers == null) throw new IllegalArgumentException("layers cannot be null");
            EnumMap<ChunkSectionLayer, LayerParity> copy =
                    new EnumMap<>(ChunkSectionLayer.class);
            copy.putAll(layers);
            layers = Map.copyOf(copy);
        }

        public Set<ChunkSectionLayer> verifiedLayers() {
            EnumSet<ChunkSectionLayer> verified = EnumSet.noneOf(ChunkSectionLayer.class);
            for (LayerParity parity : layers.values()) if (parity.matches()) verified.add(parity.layer());
            return Set.copyOf(verified);
        }

        public boolean matchesAllLayers() {
            return layers.size() == ChunkSectionLayer.values().length
                    && layers.values().stream().allMatch(LayerParity::matches);
        }
    }
}
