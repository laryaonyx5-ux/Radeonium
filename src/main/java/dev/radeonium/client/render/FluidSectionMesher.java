package dev.radeonium.client.render;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU fluid-surface prototype driven exclusively by immutable fluid snapshots.
 *
 * Emits visible top and side quads with captured tint/light metadata. It is
 * intentionally conservative and is not yet a replacement for Minecraft's
 * fluid height interpolation, flow vectors, corner averaging, underwater
 * shading or translucent render-state pipeline.
 */
public final class FluidSectionMesher {
    public FluidMesh build(FluidSectionSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot cannot be null");
        List<FluidQuad> quads = new ArrayList<>();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    short id = snapshot.fluidId(x, y, z);
                    if (id == 0) continue;

                    int tint = snapshot.tint(x, y, z);
                    int light = snapshot.packedLight(x, y, z);
                    float northWest = cornerHeight(snapshot, id, x, y, z);
                    float northEast = cornerHeight(snapshot, id, x + 1, y, z);
                    float southEast = cornerHeight(snapshot, id, x + 1, y, z + 1);
                    float southWest = cornerHeight(snapshot, id, x, y, z + 1);

                    if (snapshot.fluidIdWithHalo(x, y + 1, z) != id) {
                        add(quads, id, tint, light, 0, 1, 0,
                                new float[]{
                                        x, y + northWest, z,
                                        x, y + southWest, z + 1,
                                        x + 1, y + southEast, z + 1,
                                        x + 1, y + northEast, z
                                });
                    }
                    int westHeight = snapshot.fluidIdWithHalo(x - 1, y, z) != id
                            ? 0 : snapshot.heightEighthsWithHalo(x - 1, y, z);
                    int eastHeight = snapshot.fluidIdWithHalo(x + 1, y, z) != id
                            ? 0 : snapshot.heightEighthsWithHalo(x + 1, y, z);
                    int northHeight = snapshot.fluidIdWithHalo(x, y, z - 1) != id
                            ? 0 : snapshot.heightEighthsWithHalo(x, y, z - 1);
                    int southHeight = snapshot.fluidIdWithHalo(x, y, z + 1) != id
                            ? 0 : snapshot.heightEighthsWithHalo(x, y, z + 1);

                    if (snapshot.heightEighths(x, y, z) > westHeight) {
                        float bottom = y + westHeight / 8.0f;
                        add(quads, id, tint, light, -1, 0, 0, new float[]{
                                x, bottom, z, x, bottom, z + 1,
                                x, y + southWest, z + 1, x, y + northWest, z
                        });
                    }
                    if (snapshot.heightEighths(x, y, z) > eastHeight) {
                        float bottom = y + eastHeight / 8.0f;
                        add(quads, id, tint, light, 1, 0, 0, new float[]{
                                x + 1, bottom, z + 1, x + 1, bottom, z,
                                x + 1, y + northEast, z, x + 1, y + southEast, z + 1
                        });
                    }
                    if (snapshot.heightEighths(x, y, z) > northHeight) {
                        float bottom = y + northHeight / 8.0f;
                        add(quads, id, tint, light, 0, 0, -1, new float[]{
                                x + 1, bottom, z, x, bottom, z,
                                x, y + northWest, z, x + 1, y + northEast, z
                        });
                    }
                    if (snapshot.heightEighths(x, y, z) > southHeight) {
                        float bottom = y + southHeight / 8.0f;
                        add(quads, id, tint, light, 0, 0, 1, new float[]{
                                x, bottom, z + 1, x + 1, bottom, z + 1,
                                x + 1, y + southEast, z + 1, x, y + southWest, z + 1
                        });
                    }
                }
            }
        }
        return new FluidMesh(quads);
    }

    /**
     * Estimates a fluid surface corner from the four same-fluid cells sharing
     * that corner. Tall samples receive the stronger weight used by Minecraft's
     * fluid corner averaging; a same-fluid block above makes the corner full.
     * At section borders, only available in-section samples are used until a
     * neighbor halo is captured.
     */
    private static float cornerHeight(FluidSectionSnapshot snapshot, short id,
                                      int cornerX, int y, int cornerZ) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                if (snapshot.fluidIdWithHalo(x, y + 1, z) == id) {
                    return 1.0f;
                }
            }
        }
        float weightedHeight = 0.0f;
        int totalWeight = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                if (snapshot.fluidIdWithHalo(x, y, z) != id) continue;
                float height = snapshot.heightEighthsWithHalo(x, y, z) / 8.0f;
                int weight = height >= 0.8f ? 10 : 1;
                weightedHeight += height * weight;
                totalWeight += weight;
            }
        }
        return totalWeight == 0 ? 0.0f : weightedHeight / totalWeight;
    }


    private static void add(List<FluidQuad> out, short id, int tint, int light,
                            float nx, float ny, float nz,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        // The arguments describe the two lower edge endpoints. Build a
        // vertical quad by raising both endpoints; do not mix the first
        // endpoint's Y coordinate into its final Z coordinate.
        add(out, id, tint, light, nx, ny, nz,
                new float[]{x0,y0,z0, x1,y0,z1, x1,y1,z1, x0,y1,z0});
    }

    private static void add(List<FluidQuad> out, short id, int tint, int light,
                            float nx, float ny, float nz, float[] positions) {
        out.add(new FluidQuad(id, tint, light, nx, ny, nz, positions));
    }

    public static final class FluidMesh {
        private final List<FluidQuad> quads;
        private FluidMesh(List<FluidQuad> quads) { this.quads = List.copyOf(quads); }
        public List<FluidQuad> quads() { return quads; }
        public int quadCount() { return quads.size(); }
        public int quadCount(short fluidId) {
            int count = 0;
            for (FluidQuad quad : quads) if (quad.fluidId() == fluidId) count++;
            return count;
        }
    }

    public record FluidQuad(short fluidId, int tint, int packedLight,
                            float normalX, float normalY, float normalZ,
                            float[] positions) {
        public FluidQuad {
            if (fluidId == 0) throw new IllegalArgumentException("Fluid quad needs a non-empty fluid ID");
            if (positions == null || positions.length != 12) {
                throw new IllegalArgumentException("Fluid quad needs four finite vertices");
            }
            positions = positions.clone();
            boolean axisNormal =
                    (Math.abs(normalX) == 1.0f && normalY == 0.0f && normalZ == 0.0f)
                    || (normalX == 0.0f && Math.abs(normalY) == 1.0f && normalZ == 0.0f)
                    || (normalX == 0.0f && normalY == 0.0f && Math.abs(normalZ) == 1.0f);
            if (!Float.isFinite(normalX) || !Float.isFinite(normalY) || !Float.isFinite(normalZ)
                    || !axisNormal) {
                throw new IllegalArgumentException("Fluid normal must be a unit axis vector");
            }
            for (float value : positions) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Fluid geometry must be finite");
            }
        }
        @Override public float[] positions() { return positions.clone(); }
    }
}
