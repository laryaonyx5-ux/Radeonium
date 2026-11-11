package dev.radeonium.client.gpu;

/**
 * Rendering backends Radeonium may encounter.
 *
 * Keep backend identity separate from GPU vendor. In particular, do not
 * call OpenGL functions when Minecraft is running on Vulkan.
 */
public enum GraphicsBackend {
    OPENGL,
    VULKAN,
    OTHER,
    UNKNOWN;

    public static GraphicsBackend fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }

        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("vulkan")) {
            return VULKAN;
        }
        if (normalized.contains("opengl") || normalized.equals("gl")) {
            return OPENGL;
        }
        return OTHER;
    }
}
