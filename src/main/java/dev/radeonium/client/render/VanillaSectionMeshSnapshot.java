package dev.radeonium.client.render;

/**
 * Immutable metadata copied from a live Minecraft 26.3 SectionMesh.
 *
 * The counts describe the vanilla compiled draw groups; they are not copied
 * vertex data and must not be submitted as Radeonium geometry.
 */
public record VanillaSectionMeshSnapshot(
        int solidIndexCount,
        int cutoutIndexCount,
        int translucentIndexCount,
        boolean hasTranslucentGeometry,
        int renderableBlockEntityCount,
        long compileTaskStartTime
) {
    public int totalIndexCount() {
        return solidIndexCount + cutoutIndexCount + translucentIndexCount;
    }

    public boolean hasAnyTerrainGeometry() {
        return totalIndexCount() > 0;
    }
}
