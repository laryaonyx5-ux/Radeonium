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

- Samples frame intervals at `LevelRenderer.endFrame`, giving the scheduler a full renderer-frame boundary rather than timing only the chunk-compilation phase.
- Adapts synchronous section rebuilds to recent pacing and measured rebuild cost: up to three on CPUs with at least 12 logical processors, up to two on CPUs with at least six, and one on smaller CPUs, but only while recent frames are fast. Normal pacing allows one; slow pacing allows none temporarily.
- Guarantees a periodic synchronous slot so urgent terrain updates are not deferred indefinitely.
- Routes deferred work into Minecraft's existing asynchronous section task queue.
- Leaves Minecraft responsible for task cancellation, queue ordering, section compilation and GPU uploads.
- Does not change block models, shaders, lighting, texture quality or render distance.

The goal is to reduce render-thread spikes under load while using available headroom when frame pacing is healthy. Some terrain updates may be completed later during sustained slow frames. This is a scheduling heuristic, not a guaranteed FPS increase; it must be benchmarked in-game against the unmodified build.

### Adaptive translucent chunk maintenance — second optimization
- Uses the same recent frame-interval signal to detect especially slow frames.
- Temporarily postpones the per-frame translucent-section resort pass when the previous frame took at least 25 ms.
- Does not rebuild meshes, change transparency settings, or alter geometry. Minecraft's own round-robin resort cursor is left untouched, so the pending slice can resume on a later frame.
- This trades potentially stale translucent sorting for a short period of lower background rendering work during severe frame-time spikes. It is experimental and must be checked for visible sorting artifacts and measured in-game.



### CPU-aware chunk scheduling — third optimization
- Captures the number of logical processors available to the Java runtime once at startup.
- Sets the fast-frame ceiling to one synchronous rebuild on systems with fewer than six logical processors, two on systems with six to eleven, and three on systems with twelve or more.
- The existing frame-pacing budget can still lower that cap to one or zero during normal or slow frames.
- Uses the JVM's available-processor count, which respects CPU limits imposed by the operating system or runtime environment; it does not attempt to guess physical core topology.
- This is a conservative scheduling heuristic, not a CPU overclock, thread-pool replacement, or guaranteed FPS gain. Benchmark on target hardware before treating it as beneficial.

### Radeonium toggle
- Radeonium optimizations are enabled by default.
- The default physical key is the key labelled **W on a French AZERTY keyboard**. Minecraft 26.3 uses SDL scancodes based on the US physical-key positions, so the default mapping uses the corresponding Z-position scancode; the Controls screen can remap it.
- The binding appears under the **Radeonium** category in Options → Controls.
- Press once to disable Radeonium's chunk scheduling and translucent-resort deferral; press again to re-enable them. Status is printed in chat, not in the center of the screen.
- With Radeonium disabled, the chunk rebuild redirect returns to Minecraft's synchronous path and translucent resorting is not deferred.

### GPU capability profiling — fourth development stage
- Builds a backend-neutral profile from Minecraft's active GPU, backend, and reported device features.
- Only marks optional paths as eligible when the active device explicitly reports the required capability and a valid limit.
- Unknown vendors or backends use a safe fallback profile; no OpenGL calls are made on Vulkan.
- This stage is capability gating and groundwork, not a claim that indirect drawing, persistent mapping, or another GPU fast path has already been implemented. Such paths must be added individually and benchmarked before they are enabled.

## Build

Use a Java 25 JDK and a Gradle version compatible with the selected Fabric Loom version.

```bash
gradle build
```

The built mod JAR will be in `build/libs/`.

## Important
Radeonium is experimental software. Back up worlds before testing development builds. Never assume a performance change is beneficial until it has been benchmarked on representative hardware.

### Measured-cost adaptive scheduling — consolidated update
- Measures the actual duration of each synchronous section rebuild and feeds a smoothed cost estimate back into the next frame's budget.
- When synchronous rebuilds are expensive, the fast-frame ceiling is reduced to one or two instead of blindly using the CPU's maximum count.
- Elevated JVM heap pressure limits synchronous rebuilds to one; critical pressure temporarily sends rebuilds through Minecraft's asynchronous path.
- The scheduler performs no logging or object allocation in the per-build timing path.
- This is intended to reduce render-thread spikes and adapt to the actual chunk workload instead of relying only on CPU core count. It does not replace Minecraft's chunk renderer or guarantee a higher average FPS.

### Frame pacing and stability — sixth development stage
- Smooths the chunk-compilation interval signal with an exponential moving average (75% previous estimate, 25% new sample).
- Uses separate enter/exit thresholds for fast and slow pacing, reducing rapid budget changes when measurements hover near a threshold.
- Caps a single timing sample at 100 ms so a long pause does not dominate the estimate for an extended period.
- Applies the same smoothed signal when deciding whether to postpone translucent-section maintenance.
- The signal is still sampled at the `compileSections` hook and is only a pacing proxy; it is not a direct measurement of every rendered frame or a frame-time guarantee.
- This is scheduling stabilization, not a frame limiter or a measured FPS improvement. Validate smoothness and visual correctness in-game during the final test stage.



### Renderer integration — 0.3.0 development update
- Frame pacing is now sampled at Minecraft's `LevelRenderer.endFrame` boundary. This makes the scheduler's timing signal correspond to complete renderer-frame intervals instead of the interval between chunk-compilation hooks.
- Minecraft 26.3 already uses its own MultiDrawIndirect terrain path on supported graphics devices. Radeonium detects the relevant device capabilities and leaves that backend-managed path intact; it does not issue duplicate GPU commands or force unsupported features.
- Radeonium still uses Minecraft's section mesher, mesh storage, visibility/occlusion system, upload queue, and draw submission. Replacing those systems with a Sodium-style renderer is a separate renderer implementation, not something that can be safely achieved by toggling a GPU capability flag. No such replacement is claimed in this release.


### Renderer architecture — initial foundation (0.4.0)
Radeonium now has the first renderer-owned CPU-side building blocks:
- section coordinates and 4×4×4 render-region grouping;
- immutable CPU mesh payloads and region-level mesh ownership/accounting;
- a dirty-section queue that can feed independent meshing workers;
- a section-face visibility graph that traverses only mutually open neighboring faces.

This is **architecture groundwork, not yet a replacement renderer**. The current game still draws terrain through Minecraft's renderer. The next stages must connect real Minecraft section data to this pipeline, implement a backend-neutral upload layer, and take ownership of terrain draw submission. No FPS gain is claimed for these currently unconnected primitives.
