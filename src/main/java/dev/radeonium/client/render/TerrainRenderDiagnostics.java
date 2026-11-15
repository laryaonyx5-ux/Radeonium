package dev.radeonium.client.render;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-overhead, thread-safe counters for terrain draw ownership and fallback.
 *
 * Counters are cumulative until reset and deliberately describe submitted
 * draws, not estimated GPU work or performance.
 */
public final class TerrainRenderDiagnostics {
    public enum Source {
        RADEONIUM_MODEL,
        RADEONIUM_COMPILED_GEOMETRY,
        VANILLA_FALLBACK
    }

    public record LayerSnapshot(long radeoniumModelDraws,
                                long radeoniumCompiledGeometryDraws,
                                long vanillaFallbackDraws) {
        public long totalDraws() {
            return radeoniumModelDraws + radeoniumCompiledGeometryDraws + vanillaFallbackDraws;
        }
    }

    public record Snapshot(Map<ChunkSectionLayer, LayerSnapshot> layers, long sectionsWithoutMesh) {
        public Snapshot {
            EnumMap<ChunkSectionLayer, LayerSnapshot> copy = new EnumMap<>(ChunkSectionLayer.class);
            copy.putAll(layers);
            layers = Collections.unmodifiableMap(copy);
        }

        public long totalRadeoniumDraws() {
            return layers.values().stream().mapToLong(
                    value -> value.radeoniumModelDraws() + value.radeoniumCompiledGeometryDraws()).sum();
        }

        public long totalVanillaFallbackDraws() {
            return layers.values().stream().mapToLong(LayerSnapshot::vanillaFallbackDraws).sum();
        }

        public long totalDraws() {
            return layers.values().stream().mapToLong(LayerSnapshot::totalDraws).sum();
        }
    }

    private static final int SOURCE_COUNT = Source.values().length;
    private final LongAdder[][] draws = new LongAdder[ChunkSectionLayer.values().length][SOURCE_COUNT];
    private final LongAdder sectionsWithoutMesh = new LongAdder();

    public TerrainRenderDiagnostics() {
        for (int layer = 0; layer < draws.length; layer++) {
            for (int source = 0; source < SOURCE_COUNT; source++) {
                draws[layer][source] = new LongAdder();
            }
        }
    }

    public void recordDraw(ChunkSectionLayer layer, Source source) {
        if (layer == null || source == null) {
            throw new IllegalArgumentException("layer and source are required");
        }
        draws[layer.ordinal()][source.ordinal()].increment();
    }

    public void recordSectionWithoutMesh() {
        sectionsWithoutMesh.increment();
    }

    public Snapshot snapshot() {
        EnumMap<ChunkSectionLayer, LayerSnapshot> result = new EnumMap<>(ChunkSectionLayer.class);
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            result.put(layer, new LayerSnapshot(
                    draws[layer.ordinal()][Source.RADEONIUM_MODEL.ordinal()].sum(),
                    draws[layer.ordinal()][Source.RADEONIUM_COMPILED_GEOMETRY.ordinal()].sum(),
                    draws[layer.ordinal()][Source.VANILLA_FALLBACK.ordinal()].sum()
            ));
        }
        return new Snapshot(result, sectionsWithoutMesh.sum());
    }

    public void reset() {
        for (LongAdder[] layer : draws) {
            for (LongAdder counter : layer) counter.reset();
        }
        sectionsWithoutMesh.reset();
    }
}
