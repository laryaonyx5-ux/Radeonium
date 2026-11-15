# Step 7 — Full vanilla terrain renderer replacement

## Status

**Implementation connected in code; runtime validation still pending. Radeonium now prepares and submits its own per-layer draw list and can draw from its own GPU copies, with vanilla-buffer fallback. Minecraft still owns the frame graph and pass/OIT scheduling.**

The step 6 RenderPearl submission methods are a low-level draw bridge only. They do not provide the data or frame-graph integration needed to replace Minecraft's terrain renderer.

## Minecraft 26.3 rendering constraints

The renderer must integrate with the 26.3 frame graph and RenderPearl APIs. It must not issue raw OpenGL calls or start a nested world render.

The live terrain path has several distinct responsibilities:

1. Visibility and section lifetime are prepared before the frame's render graph. `LevelExtractor` and `SectionOcclusionGraph` determine visible sections; the current Radeonium tracker only observes part of this lifecycle.
2. `LevelRenderer` prepares visible section draws and declares the main frame-graph passes.
3. Opaque terrain is submitted in the main pass. Translucent terrain has a separate ordering path; improved transparency uses the OIT stages and their targets.
4. Minecraft's `ChunkSectionsToRender` batches section geometry and may use multi-draw-indirect depending on device support.

A replacement must preserve the rest of the frame: sky, entities, block entities, weather, clouds, world border, outlines, see-through/always-on-top features, and post effects.

## Required implementation sequence

### 7.1 — Capture the real section lifecycle

**Partial implementation committed:** `0dbfdcc684ffd766c771ff93c75c49f1715e11e1`.

- The existing extractor mixin observes the visible section set and dirty-section notifications.
- `ChunkRenderPipeline` now assigns a monotonically increasing generation to each live section state.
- Asynchronous mesh callers can capture a generation with `beginMeshBuild` and install only with the matching token. Dirty notifications advance the generation; removed sections reject late results.
- The legacy install method remains for synchronous callers only.

**Still missing:** extracting the actual compiled vanilla section data, wiring the generation token through the real section compile task, and clearing/reconciling all section state on world swaps, resource reloads and render-distance grid movement. Therefore 7.1 is not complete.

### 7.2 — Build complete Minecraft section meshes
- Resolve block states and multipart/model parts using Minecraft's model system.
- Preserve face culling, biome tint, packed light, ambient occlusion, render layer, animated atlas sprite and model transforms.
- Include fluid geometry and the required modded block-renderer compatibility path.
- Keep translucent geometry and its sorting metadata separate from opaque/cutout geometry.

### 7.3 — GPU resources and shader pipeline
- Replace the prototype position/UV upload format with a vertex layout that carries the data required by Minecraft terrain shaders.
- Bind atlas textures/samplers, lightmap, fog, camera/section transforms and material state.
- Create and cache RenderPearl pipelines for the supported terrain layers and transparency modes.
- Retire buffers safely after queued GPU work has finished.

### 7.4 — Replace vanilla terrain submission behind a safe fallback
- Integrate Radeonium into the existing main frame graph at the correct terrain submission points.
- Preserve opaque/cutout batching and device capability fallbacks.
- Implement translucent ordering and the improved-transparency/OIT path before disabling vanilla translucent terrain.
- Keep vanilla terrain submission active for any layer or device path Radeonium does not fully support.

### 7.5 — Validation before enabling by default
- Build and launch on Minecraft 26.3 with OpenGL and Vulkan where available.
- Test solid, cutout, translucent blocks, water, lava, glass, foliage, emissive blocks, biome tint, lighting, fog, resource reloads, dimension changes and chunk updates.
- Compare screenshots and frame-time captures against vanilla.
- Do not claim performance gains until representative in-game benchmarks confirm them.

## Current limitations

- `MinecraftBlockModelMesher` currently extracts supplied baked quads; it does not resolve complete live section contents.
- `MinecraftMeshGpuUpload` uploads only position and packed UV data.
- `MinecraftSectionGpuCache` is not populated by the live section compiler and is not yet connected to the frame graph.
- The terrain shaders are prototypes, not complete Minecraft-compatible terrain shaders.
- Vanilla still owns all terrain draw submission.

**Safety rule:** do not cancel or suppress vanilla terrain draws until Radeonium has a validated replacement for the same render layer and frame-graph path. A partial replacement must fall back to vanilla rather than produce missing terrain.

## 7.5 — Validation record

### Automated build validation

| Check | Result | Evidence |
|---|---|---|
| Java 25 setup | Passed | GitHub Actions run #68 |
| Gradle `clean build` | Passed | Workflow step “Compile and test” completed successfully |
| Mod JAR packaging | Passed | Workflow step “Upload mod JAR” completed successfully |
| Build artifact | Produced | `radeonium-2d4997391f1fcbc833f8236e6b8d0493b0c11f22`, 80,536 bytes |
| Unit/integration test suite | Not demonstrated | No `src/test` directory is currently present |

Run #68 validated commit `2d4997391f1fcbc833f8236e6b8d0493b0c11f22`:
https://github.com/laryaonyx5-ux/Radeonium/actions/runs/43615917352

### Runtime validation — not yet performed

The following checks remain **not tested**: launching Minecraft 26.3, OpenGL rendering, Vulkan rendering, terrain correctness, translucent/OIT behavior, resource reloads, dimension changes, chunk rebuild/removal, screenshots, and frame-time comparisons.

This environment can verify the repository and its CI build, but it cannot launch the user's Minecraft client or inspect the user's graphics hardware. A successful Gradle build is not evidence that the mod renders correctly in-game.

### Final 7.5 result

**Build/package validation: passed. Full runtime validation: pending.**

Step 7.5 cannot be marked fully complete, and the full terrain replacement cannot be called finished, until steps 7.1–7.4 have supplied a real replacement path and the runtime matrix above has passed. Vanilla terrain rendering remains authoritative; no vanilla terrain draws have been disabled.


## Latest implementation progress

- Commit `0dbfdcc684ffd766c771ff93c75c49f1715e11e1` adds stale-result protection to the CPU section pipeline.
- This is infrastructure only: it does not yet feed real Minecraft-compiled section geometry into Radeonium, upload full terrain, or replace any draw calls.
- The next implementation target is the real compiled-section lifecycle bridge, followed by complete section extraction and layer-aware GPU uploads.


## Implementation update — 2026-11-15

The live section-geometry path is now connected:

- `SectionCompilerMixin` is registered in `radeonium.mixins.json` and observes the actual Minecraft 26.3 `SectionCompiler.compile` return path.
- A per-section compile generation rejects late worker results when a newer compile for the same section has started. Completed capture generations are released, and world swaps clear the retained state.
- The capture copies each compiled layer's actual vertex and index bytes, vertex format, topology, index type and draw counts before Minecraft releases its `MeshData`. This intentionally reuses Minecraft's complete model/fluid/lighting compilation rather than attempting a second incomplete block mesher.
- The render-thread GPU cache uploads those exact bytes through RenderPearl. It only exposes buffers while the corresponding CPU snapshot remains current.
- `RadeoniumChunkSectionsToRender` now prefers Radeonium-owned GPU buffers for each layer. If an upload is absent, stale, or requires the shared sequential index buffer, the draw list falls back to Minecraft's current section slice. This prevents missing terrain while asynchronous uploads are pending.
- The existing `ChunkSectionsToRender.DrawSeparate` layer submission hook submits Radeonium's prepared draw list through RenderPearl. Minecraft's frame graph still schedules the passes and owns the transparency/OIT stages; those are not replaced.

### Updated step status

| Sub-step | Code status | Remaining validation |
|---|---|---|
| 7.1 | Live compiler hook, generation guard and world-reset cleanup connected | In-game rebuild/removal/world-swap stress test |
| 7.2 | Actual compiled layer geometry copied with format and index metadata | Verify all target layers and modded-renderer compatibility in-game |
| 7.3 | RenderPearl GPU mirror connected and used by the draw-list where compatible | Validate GPU resource lifetime and both graphics backends |
| 7.4 | Radeonium-owned layer draw list/submission connected, with per-layer vanilla fallback | Verify frame-graph, translucent ordering and OIT behavior in-game |
| 7.5 | CI build and JAR packaging pass on the current implementation | Runtime matrix and performance comparison remain pending |

**Important boundary:** this is a real custom section-data upload and draw-list path, but it is not yet a complete Sodium-equivalent renderer. The Minecraft frame graph, terrain layer pipelines/shaders, and OIT pass scheduling remain Minecraft-owned. Do not describe the renderer as fully independent or claim FPS gains until runtime correctness and benchmarks pass.
