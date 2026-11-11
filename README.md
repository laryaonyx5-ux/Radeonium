# Radeonium

**Radeonium** is a client-side Minecraft Java performance mod focused on AMD Radeon GPUs, with broad hardware compatibility as a design goal.

## Target
- Minecraft Java Edition **26.3**
- Fabric Loader
- Java 25
- Fabric API

## Development status
The repository currently contains the initial Fabric client-mod scaffold. It does **not** contain performance optimizations yet. Optimizations will be added individually, measured, and kept only when they produce a real improvement without breaking rendering.

## Design goals
- Detect GPU capabilities at runtime instead of assuming every Radeon supports the same features.
- Keep safe fallback paths for different AMD generations and non-AMD GPUs.
- Prioritize chunk/render workload, CPU overhead, memory behavior, and frame-time consistency.
- Avoid degrading visual quality as a shortcut to higher FPS.
- Maintain compatibility with the Minecraft/Fabric rendering lifecycle and popular rendering mods where practical.

## Build
Use a Java 25 JDK and Gradle 9.7.1 or newer compatible with the selected Fabric Loom version.

```bash
gradle build
```

The built mod JAR will be in `build/libs/`.

## Important
Radeonium is experimental software. Back up worlds before testing development builds. Never assume a performance change is beneficial until it has been benchmarked on representative hardware.

## Current implementation

### GPU detection foundation

The first GPU-detection layer is in place:

- Normalizes GPU vendor names into AMD, NVIDIA, Intel, Apple, Qualcomm, ARM, or unknown.
- Represents the active graphics backend separately (OpenGL, Vulkan, other, or unknown).
- Stores renderer and driver information without assuming that every field is available.
- Uses an explicit unknown fallback instead of guessing a vendor.

This is the data model and classification layer only. Live device information is **not yet connected** to Minecraft's renderer. That integration is the next step and must use Minecraft's backend-neutral rendering interfaces so Radeonium can support both OpenGL and Vulkan safely.
