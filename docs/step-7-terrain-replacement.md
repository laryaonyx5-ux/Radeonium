# Step 7 — Radeonium terrain submission bridge

## Current status

**Step 7.5 implementation is complete. Step 8 is the in-game validation phase.**

Radeonium captures Minecraft's completed section meshes, keeps a bounded CPU mirror, uploads copies to RenderPearl GPU buffers, builds its own per-layer draw lists, and submits those lists from the existing `ChunkSectionsToRender.DrawSeparate` hook.

This is not a complete Sodium-equivalent renderer. Minecraft still owns the frame graph, render-layer pipelines, shader state and translucent/OIT scheduling. No FPS improvement is claimed.

## Implemented path

### 7.1 — Live section compile capture

- `SectionCompilerMixin` starts a capture at compile entry and captures the returned `SectionCompiler.Results`.
- Per-section generations reject stale asynchronous compile results.
- World reset clears retained CPU geometry.
- Captured `MeshData` is copied before Minecraft releases it.

### 7.2 — Reuse Minecraft's compiled geometry

- Radeonium mirrors the actual vertex and index bytes for each compiled layer.
- The snapshot retains vertex-format, topology, index-type and draw-count metadata.
- This reuses Minecraft's own block, fluid, lighting, tint and model compilation rather than attempting a second incomplete mesher.

### 7.3 — GPU mirror

- The render-thread cache uploads the captured byte streams through RenderPearl.
- CPU and GPU caches are bounded by section count and byte budgets.
- The pending render-thread upload queue is also bounded; when saturated, it drops the oldest pending snapshot rather than allowing an unlimited backlog.
- Uploads are exposed only while their source CPU snapshot remains current.
- Invalid index metadata is rejected before GPU upload, and draw-time compatibility checks verify vertex-buffer capacity, index-buffer presence/type/capacity and draw counts before using copied geometry.

### 7.4 — Layer draw-list submission

- Radeonium prepares a draw list per Minecraft chunk layer and submits it through the existing layer render hook.
- The copied geometry is used only when its captured vertex format matches the active layer format and its topology matches the sequential QUADS path.
- Missing, stale or incompatible copied geometry falls back to Minecraft's current section slice.
- Minecraft continues to schedule the frame-graph passes and transparency/OIT stages.

## 7.5 — Technical completion

- The section capture, bounded CPU mirror, bounded upload queue, bounded GPU mirror and per-layer submission path are connected.
- Stale section snapshots are rejected before upload and before exposure to the draw-list builder.
- Invalid or mismatched copied geometry is rejected and the live Minecraft section slice remains the fallback.
- GitHub Actions runs `gradle --no-daemon clean build` and packages the mod JAR.
- This closes the implementation milestone. Visual/runtime verification is deliberately reserved for Step 8.

## Step 8 — In-game validation

These checks are the next phase and require launching the mod in Minecraft 26.3 on the target machine:

1. Confirm the client starts without mixin or RenderPearl errors.
2. Check terrain in all layers: solid, cutout, cutout-mipped and translucent.
3. Inspect water, lava, glass, foliage, emissive blocks, biome tint, lighting and fog.
4. Break/place blocks and force chunk rebuilds; travel far enough to unload/reload sections.
5. Reload resources and change dimensions; verify no stale geometry or missing terrain.
6. Repeat on each available graphics backend (OpenGL/Vulkan) and record any backend-specific failures.
7. Compare screenshots and frame-time measurements against the same world/settings on vanilla.

Do not disable vanilla fallback or claim performance gains until correctness and representative benchmarks have been verified.

## Result

| Area | Status |
|---|---|
| Compile capture and stale-result guard | Implemented; runtime stress test pending |
| Actual compiled layer geometry mirror | Implemented; in-game layer checks pending |
| RenderPearl GPU mirror | Implemented; resource lifetime/backend checks pending |
| Radeonium per-layer draw-list submission | Connected with format/topology guard and vanilla fallback |
| Bounded capture, upload queue and GPU mirror | Implemented |
| Draw-time geometry validation and vanilla fallback | Implemented |
| Gradle build and JAR packaging | CI check |
| In-game rendering, lifecycle and graphics-backend checks | **Step 8 — pending** |

**Step 7.5 is the implementation milestone; Step 8 is the separate runtime-validation milestone.**
