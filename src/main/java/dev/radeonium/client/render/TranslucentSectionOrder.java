package dev.radeonium.client.render;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic far-to-near ordering for section-level translucent submissions.
 *
 * Individual model/fluid quads are sorted inside each Radeonium-owned section
 * upload. This second ordering layer arranges those section draws globally.
 * It is a conservative alpha-blending fallback, not a replacement for OIT:
 * intersecting translucent surfaces can still require host OIT or vanilla.
 */
public final class TranslucentSectionOrder {
    private TranslucentSectionOrder() {}

    public record Entry<T>(SectionPos section, T draw) {
        public Entry {
            if (section == null || draw == null) {
                throw new IllegalArgumentException("section and draw are required");
            }
        }
    }

    /** Returns draw values ordered by section-center distance, farthest first. */
    public static <T> List<T> farToNear(List<Entry<T>> entries, Vec3 camera) {
        if (entries == null || camera == null) {
            throw new IllegalArgumentException("entries and camera are required");
        }
        List<IndexedEntry<T>> sorted = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry<T> entry = entries.get(i);
            if (entry == null) throw new IllegalArgumentException("entries cannot contain null");
            sorted.add(new IndexedEntry<>(entry.draw(), distanceSquared(entry.section(), camera), i));
        }
        sorted.sort(Comparator.comparingDouble(IndexedEntry<T>::distanceSquared).reversed()
                .thenComparingInt(IndexedEntry<T>::ordinal));
        return sorted.stream().map(IndexedEntry::draw).toList();
    }

    static double distanceSquared(SectionPos section, Vec3 camera) {
        if (section == null || camera == null) {
            throw new IllegalArgumentException("section and camera are required");
        }
        double dx = section.x() * 16.0 + 8.0 - camera.x();
        double dy = section.y() * 16.0 + 8.0 - camera.y();
        double dz = section.z() * 16.0 + 8.0 - camera.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private record IndexedEntry<T>(T draw, double distanceSquared, int ordinal) {}
}
