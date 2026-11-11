package dev.radeonium.client.gpu;

/**
 * A backend-neutral snapshot of graphics device information.
 *
 * Values may be unknown until the active Minecraft renderer exposes them.
 * This object intentionally does not query OpenGL or Vulkan itself.
 */
public record GpuInfo(
        GpuVendor vendor,
        String vendorName,
        String rendererName,
        String driverVersion,
        GraphicsBackend backend
) {
    public GpuInfo {
        vendor = vendor == null ? GpuVendor.UNKNOWN : vendor;
        vendorName = clean(vendorName);
        rendererName = clean(rendererName);
        driverVersion = clean(driverVersion);
        backend = backend == null ? GraphicsBackend.UNKNOWN : backend;
    }

    public static GpuInfo unavailable() {
        return new GpuInfo(
                GpuVendor.UNKNOWN,
                "Unknown",
                "Not detected yet",
                "Unknown",
                GraphicsBackend.UNKNOWN
        );
    }

    public boolean isAmd() {
        return vendor == GpuVendor.AMD;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "Unknown" : value.trim();
    }
}
