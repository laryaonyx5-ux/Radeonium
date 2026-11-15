package dev.radeonium.client.render;

import java.util.List;

/**
 * Conservative section frustum test.
 * Plane equations use ax + by + cz + d >= 0 for points inside.
 * The positive-vertex AABB test rejects only fully outside sections.
 */
public final class SectionFrustumCuller {
    public record Plane(double a, double b, double c, double d) {
        public Plane {
            if (!Double.isFinite(a) || !Double.isFinite(b)
                    || !Double.isFinite(c) || !Double.isFinite(d)
                    || (a == 0.0 && b == 0.0 && c == 0.0)) {
                throw new IllegalArgumentException("frustum plane must be finite and have a normal");
            }
        }
        double distance(double x, double y, double z) {
            return a * x + b * y + c * z + d;
        }
    }

    private final List<Plane> planes;

    public SectionFrustumCuller(List<Plane> planes) {
        if (planes == null || planes.size() != 6 || planes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("exactly six valid frustum planes are required");
        }
        this.planes = List.copyOf(planes);
    }

    public boolean intersects(SectionPos section) {
        if (section == null) throw new IllegalArgumentException("section cannot be null");
        double minX = (double) section.x() * 16.0;
        double minY = (double) section.y() * 16.0;
        double minZ = (double) section.z() * 16.0;
        double maxX = minX + 16.0;
        double maxY = minY + 16.0;
        double maxZ = minZ + 16.0;

        for (Plane plane : planes) {
            double px = plane.a() >= 0.0 ? maxX : minX;
            double py = plane.b() >= 0.0 ? maxY : minY;
            double pz = plane.c() >= 0.0 ? maxZ : minZ;
            if (plane.distance(px, py, pz) < 0.0) return false;
        }
        return true;
    }

    /** Removes rejected sections without allocating a second list. */
    public int filterInPlace(List<SectionPos> sections) {
        if (sections == null) throw new IllegalArgumentException("sections cannot be null");
        int write = 0;
        for (int read = 0; read < sections.size(); read++) {
            SectionPos section = sections.get(read);
            if (intersects(section)) sections.set(write++, section);
        }
        while (sections.size() > write) sections.remove(sections.size() - 1);
        return write;
    }
}
