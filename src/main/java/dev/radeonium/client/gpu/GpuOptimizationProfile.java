package dev.radeonium.client.gpu;

/**
 * A conservative description of which optional GPU paths are safe to consider.
 *
 * This class deliberately does not issue graphics API calls. A feature is
 * eligible only when Minecraft's active device explicitly reports support.
 */
public record GpuOptimizationProfile(
        Strategy strategy,
        boolean indirectDraw,
        boolean multiDrawIndirect,
        boolean persistentMapping,
        boolean shaderDrawParameters
) {
    public enum Strategy {
        AMD_CAPABILITY_GATED,
        GENERIC_CAPABILITY_GATED,
        SAFE_FALLBACK
    }

    public static GpuOptimizationProfile from(GpuInfo info, GpuCapabilities caps) {
        if (info == null || caps == null
                || info.vendor() == GpuVendor.UNKNOWN
                || info.backend() == GraphicsBackend.UNKNOWN
                || info.backend() == GraphicsBackend.OTHER) {
            return new GpuOptimizationProfile(Strategy.SAFE_FALLBACK, false, false, false, false);
        }

        boolean indirect = caps.drawIndirect() && caps.maxDrawIndirectCount() > 0;
        boolean multiDraw = indirect && caps.multiDrawIndirect();
        boolean persistent = caps.persistentMapping();
        boolean shaderParameters = caps.shaderDrawParameters();

        Strategy strategy = info.isAmd()
                ? Strategy.AMD_CAPABILITY_GATED
                : Strategy.GENERIC_CAPABILITY_GATED;

        return new GpuOptimizationProfile(
                strategy,
                indirect,
                multiDraw,
                persistent,
                shaderParameters
        );
    }
}
