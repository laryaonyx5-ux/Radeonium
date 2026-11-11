package dev.radeonium.client.gpu;

import java.util.Locale;

/**
 * Classifies vendor strings supplied by the active graphics backend.
 *
 * This class does not inspect the machine directly. The renderer adapter
 * will provide the actual strings once the graphics device is initialized.
 */
public final class GpuVendorDetector {
    private GpuVendorDetector() {
    }

    public static GpuVendor detect(String vendorName, String rendererName) {
        String text = ((vendorName == null ? "" : vendorName) + " "
                + (rendererName == null ? "" : rendererName))
                .toLowerCase(Locale.ROOT);

        if (containsAny(text, "advanced micro devices", "amd", "ati technologies",
                "radeon") ) {
            return GpuVendor.AMD;
        }
        if (containsAny(text, "nvidia", "geforce", "quadro", "tesla")) {
            return GpuVendor.NVIDIA;
        }
        if (containsAny(text, "intel", "iris", "uhd graphics", "arc graphics")) {
            return GpuVendor.INTEL;
        }
        if (containsAny(text, "apple", "apple m1", "apple m2", "apple m3", "apple m4", "apple m5")) {
            return GpuVendor.APPLE;
        }
        if (containsAny(text, "qualcomm", "adreno")) {
            return GpuVendor.QUALCOMM;
        }
        if (containsAny(text, "arm", "mali")) {
            return GpuVendor.ARM;
        }

        return GpuVendor.UNKNOWN;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
