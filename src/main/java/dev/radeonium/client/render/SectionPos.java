package dev.radeonium.client.render;

/** Stable integer coordinates for a 16x16x16 world section. */
public record SectionPos(int x, int y, int z) {
    public SectionPos offset(SectionFace face) {
        return new SectionPos(x + face.dx(), y + face.dy(), z + face.dz());
    }

    public RegionPos region() {
        return new RegionPos(Math.floorDiv(x, 4), Math.floorDiv(y, 4), Math.floorDiv(z, 4));
    }

    /** Local index inside a 4x4x4 render region. */
    public int regionIndex() {
        return Math.floorMod(x, 4)
                | (Math.floorMod(y, 4) << 2)
                | (Math.floorMod(z, 4) << 4);
    }

    public record RegionPos(int x, int y, int z) {
    }
}
