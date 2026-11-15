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

                    float top = y + snapshot.heightEighths(x, y, z) / 8.0f;
                    int tint = snapshot.tint(x, y, z);
                    int light = snapshot.packedLight(x, y, z);

                    if (y == 15 || snapshot.fluidId(x, y + 1, z) != id) {
                        float northWest = cornerHeight(snapshot, id, x, y, z);
                        float northEast = cornerHeight(snapshot, id, x + 1, y, z);
                        float southEast = cornerHeight(snapshot, id, x + 1, y, z + 1);
                        float southWest = cornerHeight(snapshot, id, x, y, z + 1);
                        add(quads, id, tint, light, 0, 1, 0,
                                new float[]{
                                        x, y + northWest, z,
                                        x + 1, y + northEast, z,
                                        x + 1, y + southEast, z + 1,
                                        x, y + southWest, z + 1
                                });
                    }
                    int westHeight = x == 0 || snapshot.fluidId(x - 1, y, z) != id
                            ? 0 : snapshot.heightEighths(x - 1, y, z);
                    int eastHeight = x == 15 || snapshot.fluidId(x + 1, y, z) != id
                            ? 0 : snapshot.heightEighths(x + 1, y, z);
                    int northHeight = z == 0 || snapshot.fluidId(x, y, z - 1) != id
                            ? 0 : snapshot.heightEighths(x, y, z - 1);
                    int southHeight = z == 15 || snapshot.fluidId(x, y, z + 1) != id
                            ? 0 : snapshot.heightEighths(x, y, z + 1);

                    if (snapshot.heightEighths(x, y, z) > westHeight) {
                        add(quads, id, tint, light, -1, 0, 0,
                                x, y + westHeight / 8.0f, z, x, top, z + 1);
                    }
                    if (snapshot.heightEighths(x, y, z) > eastHeight) {
                        add(quads, id, tint, light, 1, 0, 0,
                                x + 1, y + eastHeight / 8.0f, z + 1, x + 1, top, z);
                    }
                    if (snapshot.heightEighths(x, y, z) > northHeight) {
                        add(quads, id, tint, light, 0, 0, -1,
                                x + 1, y + northHeight / 8.0f, z, x, top, z);
                    }
                    if (snapshot.heightEighths(x, y, z) > southHeight) {
                        add(quads, id, tint, light, 0, 0, 1,
                                x, y + southHeight / 8.0f, z + 1, x + 1, top, z + 1);
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
        if (y < 15) {
            for (int dx = -1; dx <= 0; dx++) {
                for (int dz = -1; dz <= 0; dz++) {
                    int x = cornerX + dx;
                    int z = cornerZ + dz;
                    if (x >= 0 && x < 16 && z >= 0 && z < 16
                            && snapshot.fluidId(x, y + 1, z) == id) {
                        return 1.0f;
                    }
                }
            }
        }
        float weightedHeight = 0.0f;
        int totalWeight = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                if (x < 0 || x >= 16 || z < 0 || z >= 16
                        || snapshot.fluidId(x, y, z) != id) continue;
                float height = snapshot.heightEighths(x, y, z) / 8.0f;
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
            positions = positions.clone();
            if (positions.length != 12) throw new IllegalArgumentException("Fluid quad needs 4 vertices");
            if (!Float.isFinite(normalX) || !Float.isFinite(normalY) || !Float.isFinite(normalZ)) {
                throw new IllegalArgumentException("Fluid normal must be finite");
            }
            for (float value : positions) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Fluid geometry must be finite");
            }
        }
        @Override public float[] positions() { return positions.clone(); }
    }
}
