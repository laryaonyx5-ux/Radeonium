package dev.radeonium.client.gpu;

/**
 * Capabilities reported by Minecraft's active graphics device.
 *
 * These values describe what the device reports as supported. They do not
 * automatically enable an optimization; each renderer feature must still
 * be implemented, validated, and benchmarked separately.
 */
public record GpuCapabilities(
        boolean drawIndirect,
        boolean multiDrawIndirect,
        boolean multiDrawDirectInterleaved,
        boolean multiDrawDirectSeparate,
        boolean shaderDrawParameters,
        boolean nonZeroFirstInstance,
        boolean persistentMapping,
        boolean wireframeFillMode,
        int maxDrawIndirectCount,
        int maxMultiDrawDirectInterleavedCount,
        int maxTextureSize,
        int maxAnisotropy,
        int maxColorAttachments,
        int minUniformOffsetAlignment,
        long maxMemoryAllocationSize
) {
    public static GpuCapabilities unavailable() {
        return new GpuCapabilities(
                false, false, false, false, false, false, false, false,
                0, 0, 0, 0, 0, 0, 0L
        );
    }
}
