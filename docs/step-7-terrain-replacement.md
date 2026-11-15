# Step 7 — Radeonium terrain submission bridge

## Current status

**The code path is connected and guarded by vanilla fallback. Step 7.5 is only partially complete: the automated build is passing, but in-game validation has not been performed.**

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
- Uploads are exposed only while their source CPU snapshot remains current.

### 7.4 — Layer draw-list submission

- Radeonium prepares a draw list per Minecraft chunk layer and submits it through the existing layer render hook.
- The copied geometry is used only when its captured vertex format matches the active layer format and its topology matches the sequential QUADS path.
- Missing, stale or incompatible copied geometry falls back to Minecraft's current section slice.
- Minecraft continues to schedule the frame-graph passes and transparency/OIT stages.

## 7.5 — Validation

### Automated checks

- GitHub Actions runs `gradle --no-daemon clean build` on pushes and pull requests.
- The workflow packages the mod JAR as an artifact.
- A successful build proves compilation and packaging only; it does not prove that mixins apply correctly at runtime or that terrain renders correctly.
- There is currently no dedicated unit/integration test suite for the live renderer path.

### Runtime checks still required

These checks require launching the mod in Minecraft 26.3 on the target machine:

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
| Gradle build and JAR packaging | CI check |
| Full runtime validation and benchmark | **Pending — must be tested in Minecraft 26.3** |

**Step 7.5 is not complete until the runtime checklist passes.** The user-side test is necessary because CI cannot launch the user's Minecraft client or validate their graphics hardware.
