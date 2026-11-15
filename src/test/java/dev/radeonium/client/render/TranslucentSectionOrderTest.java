package dev.radeonium.client.render;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranslucentSectionOrderTest {
    @Test
    void sortsSectionsFarthestToNearestFromCamera() {
        var near = new SectionPos(0, 0, 0);
        var far = new SectionPos(4, 0, 0);

        assertEquals(List.of("far", "near"), TranslucentSectionOrder.farToNear(
                List.of(
                        new TranslucentSectionOrder.Entry<>(near, "near"),
                        new TranslucentSectionOrder.Entry<>(far, "far")
                ),
                new Vec3(8, 8, 8)
        ));
    }

    @Test
    void preservesInputOrderForEqualDistances() {
        var left = new SectionPos(-1, 0, 0);
        var right = new SectionPos(0, 0, 0);

        assertEquals(List.of("left", "right"), TranslucentSectionOrder.farToNear(
                List.of(
                        new TranslucentSectionOrder.Entry<>(left, "left"),
                        new TranslucentSectionOrder.Entry<>(right, "right")
                ),
                new Vec3(0, 8, 8)
        ));
    }

    @Test
    void rejectsMissingInputsAndNullEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> TranslucentSectionOrder.farToNear(null, Vec3.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> TranslucentSectionOrder.farToNear(List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> TranslucentSectionOrder.farToNear(
                        java.util.Arrays.asList((TranslucentSectionOrder.Entry<String>) null), Vec3.ZERO));
    }
}
