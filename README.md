# Radeonium

**Radeonium** is a client-side Minecraft Java performance mod focused on AMD Radeon GPUs, with broad hardware compatibility as a design goal.

## Target
- Minecraft Java Edition **26.3**
- Fabric Loader
- Java 25
- Fabric API

## Design goals
- Detect GPU capabilities at runtime instead of assuming every Radeon supports the same features.
- Keep safe fallback paths for different AMD generations and non-AMD GPUs.
- Prioritize chunk/render workload, CPU overhead, memory behavior, and frame-time consistency.
- Avoid degrading visual quality as a shortcut to higher FPS.
- Maintain compatibility with the Minecraft/Fabric rendering lifecycle and popular rendering mods where practical.

## Development status

Radeonium is under active development. Each optimization is added separately, compiled in CI, and tested in-game before it is treated as proven. No fixed FPS gain is promised.

## Current implementation

### GPU detection foundation
- Normalizes GPU vendor names into AMD, NVIDIA, Intel, Apple, Qualcomm, ARM, or unknown.
- Represents the active graphics backend separately (OpenGL, Vulkan, other, or unknown).
- Reads the active device name, vendor, driver and backend from Minecraft's RenderPearl device information.
- Reads reported GPU features and limits, using conservative fallback values if information is unavailable.

The capability snapshot is informational. A reported feature is not automatically activated; each optimization must have a compatible implementation and pass correctness and performance tests.

### Chunk rebuild scheduling — first optimization
Radeonium now hooks Minecraft 26.3's `LevelRenderer.compileSections` path.

- Allows at most **one synchronous chunk-section rebuild per frame**.
- Routes additional synchronous rebuild requests into Minecraft's existing asynchronous section task queue.
- Leaves Minecraft responsible for task cancellation, queue ordering, section compilation and GPU uploads.
- Does not change block models, shaders, lighting, texture quality or render distance.

The goal is to reduce render-thread frame-time spikes when many nearby sections need rebuilding at once. The trade-off is that some updated terrain may appear a little later because more work is completed asynchronously. This is a first scheduling optimization, not yet a measured FPS result.

## Build

Use a Java 25 JDK and a Gradle version compatible with the selected Fabric Loom version.

```bash
gradle build
```

The built mod JAR will be in `build/libs/`.

## Important
Radeonium is experimental software. Back up worlds before testing development builds. Never assume a performance change is beneficial until it has been benchmarked on representative hardware.
