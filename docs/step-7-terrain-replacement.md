# Step 7 — Full vanilla terrain renderer replacement

## Status

**In progress — architecture mapped; no vanilla terrain draw path has been replaced yet.**

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
- Resolve each visible `RenderSection` to its current compiled section data.
- Track section rebuilds, removals, world changes, resource reloads, and render-distance grid movement.
- Reject stale asynchronous results using a section generation/version token.

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
