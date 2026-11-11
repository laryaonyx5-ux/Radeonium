package dev.radeonium.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.radeonium.client.RadeoniumClient;

/**
 * Reads the graphics device selected by Minecraft itself.
 *
 * This uses RenderPearl's backend-neutral device information, so it works
 * without making OpenGL-only assumptions and can also run with Vulkan.
 */
public final class GpuDetector {
    private static volatile GpuInfo current = GpuInfo.unavailable();

    private GpuDetector() {
    }

    public static GpuInfo current() {
        return current;
    }

    public static GpuInfo detectAndLog() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            current = GpuInfo.unavailable();
            RadeoniumClient.LOGGER.warn("Radeonium could not read the graphics device yet.");
            return current;
        }

        try {
            DeviceInfo info = device.getDeviceInfo();
            current = new GpuInfo(
                    GpuVendorDetector.detect(info.vendorName(), info.name()),
                    info.vendorName(),
                    info.name(),
                    info.driverInfo(),
                    GraphicsBackend.fromName(info.backendName())
            );

            RadeoniumClient.LOGGER.info(
                    "Detected GPU: {} | vendor: {} | driver: {} | backend: {}",
                    current.rendererName(),
                    current.vendorName(),
                    current.driverVersion(),
                    current.backend()
            );

            if (current.isAmd()) {
                RadeoniumClient.LOGGER.info("AMD Radeon hardware detected; Radeonium AMD capability checks are available.");
            }
        } catch (RuntimeException exception) {
            current = GpuInfo.unavailable();
            RadeoniumClient.LOGGER.warn("Radeonium could not inspect the active graphics device.", exception);
        }

        return current;
    }
}
