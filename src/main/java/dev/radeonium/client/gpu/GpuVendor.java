package dev.radeonium.client.gpu;

/**
 * GPU vendors recognized by Radeonium.
 *
 * UNKNOWN is deliberately retained as a safe fallback: an unrecognized
 * device must never be treated as an AMD GPU by accident.
 */
public enum GpuVendor {
    AMD,
    NVIDIA,
    INTEL,
    APPLE,
    QUALCOMM,
    ARM,
    UNKNOWN
}
