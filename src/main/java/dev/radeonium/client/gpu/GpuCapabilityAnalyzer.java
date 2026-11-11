package dev.radeonium.client.gpu;

import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;

/**
 * Converts RenderPearl's reported device features and limits into a stable
 * Radeonium capability snapshot.
 */
public final class GpuCapabilityAnalyzer {
    private GpuCapabilityAnalyzer() {
    }

    public static GpuCapabilities analyze(DeviceInfo info) {
        if (info == null || info.features() == null || info.limits() == null) {
            return GpuCapabilities.unavailable();
        }

        DeviceFeatures features = info.features();
        DeviceLimits limits = info.limits();

        return new GpuCapabilities(
                features.drawIndirect(),
                features.multiDrawIndirect(),
                features.multiDrawDirectInterleaved(),
                features.multiDrawDirectSeparate(),
                features.shaderDrawParameters(),
                features.persistentMapping(),
                features.wireframeFillMode(),
                nonNegative(limits.maxDrawIndirectDrawCount()),
                nonNegative(limits.maxMultiDrawDirectInterleavedDrawCount()),
                nonNegative(limits.maxTextureSize()),
                nonNegative(limits.maxAnisotropy()),
                Math.max(0L, limits.maxMemoryAllocationSize())
        );
    }

    private static int nonNegative(int value) {
        return Math.max(0, value);
    }
}
