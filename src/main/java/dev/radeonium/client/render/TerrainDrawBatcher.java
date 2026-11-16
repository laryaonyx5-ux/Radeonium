package dev.radeonium.client.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Stable identity-based grouping for opaque terrain draw submissions.
 *
 * It changes submission order only; it does not merge draws or alter their
 * per-section uniforms, index ranges, or vertex data. Callers must not use it
 * for translucent geometry whose order affects blending.
 */
public final class TerrainDrawBatcher {
    private TerrainDrawBatcher() {}

    public static <T> List<T> groupByVertexBuffer(
            List<T> draws, Function<? super T, ?> vertexBuffer
    ) {
        if (draws == null || vertexBuffer == null) {
            throw new IllegalArgumentException("draws and vertexBuffer are required");
        }

        Map<Object, List<T>> groups = new IdentityHashMap<>();
        List<Object> firstSeenBuffers = new ArrayList<>();
        List<T> nullBufferDraws = new ArrayList<>();

        for (T draw : draws) {
            if (draw == null) throw new IllegalArgumentException("draw entries cannot be null");
            Object buffer = vertexBuffer.apply(draw);
            if (buffer == null) {
                nullBufferDraws.add(draw);
                continue;
            }
            List<T> group = groups.get(buffer);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(buffer, group);
                firstSeenBuffers.add(buffer);
            }
            group.add(draw);
        }

        List<T> result = new ArrayList<>(draws.size());
        for (Object buffer : firstSeenBuffers) result.addAll(groups.get(buffer));
        // Unknown handles are kept together at the end; they are never conflated.
        result.addAll(nullBufferDraws);
        return result;
    }
}
