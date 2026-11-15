# Radeonium — Full renderer target

## Product goal

Radeonium is intended to grow into an independent Minecraft terrain renderer, not remain a scheduler or a mirror of Minecraft's draw submissions.

The target is a renderer that owns the complete terrain path while preserving Minecraft's visual behavior and supporting the active graphics backend. Performance work must not use lower texture quality, missing lighting, broken transparency, reduced render distance, or disabled visual effects as shortcuts.

A full replacement is a long-term target. The current implementation is only an intermediate bridge and must continue to use safe vanilla fallbacks until each replacement stage is complete and validated.


## Milestone A — renderer contract (implementation)

**Status: implementation complete; automated CI verification pending.**

Milestone A establishes the API boundary before replacing any Minecraft terrain behavior.

### Code delivered

- `RendererContract` defines backend-neutral terrain layers, fallback reasons, world/generation-scoped section build tickets, immutable mesh payloads, opaque GPU mesh handles, frame context and draw descriptors.
- `SectionSnapshot` is the boundary for immutable data captured from Minecraft. It must not expose live world, chunk or mutable game objects to worker threads.
- `SectionMesher` is the CPU-only worker contract.
- `GpuResourceOwner` is the Radeonium GPU allocation/release contract.
- `TerrainPassSubmitter` is the terrain draw submission contract.
- `TerrainRenderer` defines the renderer lifecycle and section invalidation entry points.
- `RendererThreadGuard` binds GPU-facing operations to one render thread and rejects calls from other threads.
- Mesh payload constructors validate vertex/index byte counts and defensively copy their arrays, preventing callers from mutating submitted CPU mesh data after construction.
- JUnit 5 tests cover defensive copying, invalid mesh metadata, invalid generation tokens and render-thread ownership.

### Thread and ownership rules

| Data or operation | Owner / permitted thread |
|---|---|
| Read Minecraft world, block, biome and lighting state | Minecraft-approved game/build thread only |
| Immutable section snapshot after capture | Radeonium-owned; safe to hand to workers |
| Model resolution and CPU mesh generation | Worker-safe only when operating on immutable snapshot data |
| Radeonium CPU mesh payload | Radeonium-owned immutable data |
| GPU buffer creation, upload, replacement and release | Radeonium GPU owner on the bound render thread |
| Render pipeline binding and draw submission | Render thread, inside the host-provided frame/pass lifecycle |
| Minecraft mesh buffers, frame graph, shaders and live render state | Minecraft-owned unless a later milestone explicitly replaces that responsibility |

### Vanilla hand-off and fallback boundary

- Until a later milestone explicitly takes ownership, Minecraft continues to compile terrain and owns its original mesh buffers.
- Radeonium must never close, mutate or retain a borrowed Minecraft GPU buffer beyond the documented lifetime supplied by its adapter.
- Radeonium may release only GPU resources created by its own `GpuResourceOwner`.
- A missing, stale, unsupported or invalid Radeonium mesh must select the corresponding vanilla section/layer path; it must never silently omit terrain.
- Radeonium and vanilla must not both submit the same terrain layer for the same section in one pass.
- World changes and resource reloads invalidate outstanding build tickets so stale worker results cannot be installed into a new world lifetime.

### Acceptance checklist

- [x] Backend-neutral renderer interfaces and data contracts exist.
- [x] Thread-affinity guard exists for GPU-facing operations.
- [x] Immutable mesh payloads validate metadata and copy mutable arrays.
- [x] Ownership and vanilla fallback rules are documented.
- [x] Automated tests cover the contract invariants.
- [ ] GitHub Actions build and test job completes successfully for the commit containing this milestone.

Milestone B must not begin until the final CI item is green. This milestone does not yet implement Minecraft model resolution, a new terrain mesher, GPU uploads, shaders, pipelines or transparency; those belong to later milestones.

## What "Radeonium owns the renderer" means

Radeonium must eventually own these responsibilities:

1. **World and section input**
   - Observe block, biome, light, fluid, block-entity and resource changes.
   - Track section lifetime, dirty reasons and dependencies on neighboring sections.
   - Keep all world access on permitted game threads and pass immutable snapshots to workers.

2. **Minecraft-compatible model resolution and meshing**
   - Resolve the actual baked block models, multipart models, model variants and modded model hooks.
   - Generate solid, cutout, cutout-mipped and translucent geometry.
   - Preserve atlas UVs, tint indices, biome colors, packed light, ambient occlusion, face culling, emissive behavior and animated texture references.
   - Generate fluid surfaces and handle special geometry without silently dropping unsupported content.
   - Build section meshes asynchronously with bounded queues, cancellation and stale-generation rejection.

3. **Radeonium-owned mesh and GPU resource management**
   - Define stable vertex formats and material descriptors.
   - Own CPU mesh storage, GPU buffers, allocation/reuse, upload scheduling and safe resource disposal.
   - Support backend-neutral device abstractions; do not issue OpenGL-specific calls on Vulkan or assume a particular GPU feature exists.

4. **Radeonium shader and material system**
   - Supply and manage its own shader programs and material bindings for terrain.
   - Reproduce Minecraft's required lighting, fog, texture sampling, tint, emissive and cutout behavior.
   - Provide explicit capability-based shader variants and conservative fallbacks.
   - Keep resource-pack and mod compatibility in scope; shader replacement must not silently invalidate user assets.

5. **Radeonium render pipelines and frame integration**
   - Own terrain pipeline definitions, render-state setup, draw batching and submission.
   - Integrate with Minecraft's frame lifecycle without submitting duplicate terrain or corrupting other render passes.
   - Keep opaque, cutout and translucent work in correct passes with correct depth, culling and blending state.

6. **Correct transparency**
   - Preserve correct depth testing and blending for water, glass, ice, particles and modded translucent materials.
   - Implement an explicit sorting/ordering strategy and evaluate order-independent transparency only where the backend supports it and visual tests prove it correct.
   - Never assume that simply sorting whole chunks is sufficient for intersecting translucent surfaces.

7. **Visibility and lighting correctness**
   - Own or deliberately integrate section visibility, frustum/occlusion decisions and neighbor invalidation.
   - Preserve Minecraft lighting, ambient occlusion, biome tint, fog and dimension-specific effects.
   - Avoid visual holes, stale faces, flicker and seams at section boundaries.

8. **Diagnostics and fallback**
   - Detect unsupported model types, shaders, materials and device capabilities.
   - Keep a per-feature fallback to Minecraft until the Radeonium path is known to be correct.
   - Add debug counters and optional visual diagnostics for mesh rebuilds, uploads, draw calls, memory and rejected geometry.

## Quality requirements

A stage is not accepted just because it compiles. Before a feature can replace the corresponding vanilla path, it must have:

- build/CI coverage and focused automated tests where practical;
- explicit compatibility checks and a safe fallback;
- visual comparison against vanilla for the same scene and settings;
- checks for all terrain layers, lighting, tint, fluids, animated textures and resource reloads;
- lifecycle checks for block changes, chunk rebuilds, unload/reload and dimension changes;
- backend checks for every supported graphics backend;
- frame-time and memory measurements on representative scenes.

No FPS gain is claimed until measured. A performance improvement is not acceptable if it causes visible regressions.

## Incremental implementation plan

The milestones below are architectural targets, not claims that these systems already exist.

| Milestone | Target | Completion condition |
|---|---|---|
| A — renderer contract | Define renderer-owned data types, thread rules, ownership and vanilla hand-off boundaries | Interfaces, invariant tests and green CI |
| B — complete Minecraft mesher | Replace the geometry mirror with Radeonium-generated section meshes while preserving model and lighting semantics | Model/layer parity tests and visual comparison |
| C — GPU resource owner | Radeonium owns mesh allocation, uploads, reuse and disposal | No dependence on vanilla mesh buffers for Radeonium draws |
| D — terrain shader/material system | Radeonium binds its own terrain shaders and materials | Visual parity for opaque/cutout terrain, tint, light, fog and emissive content |
| E — independent terrain pipelines | Radeonium owns terrain pipeline state and draw submission | Terrain draws no longer depend on Minecraft's terrain draw pipeline |
| F — transparency renderer | Radeonium owns translucent terrain ordering/blending | Water, glass and intersecting surfaces pass visual tests |
| G — visibility and lifecycle | Radeonium owns or explicitly integrates visibility, invalidation and reload handling | No missing/stale geometry under movement and world changes |
| H — full renderer validation | Exercise supported backends, packs, mods and performance scenarios | Correctness and benchmark report; only then consider vanilla terrain fallback optional |

The order may be adjusted when implementation dependencies require it, but visual correctness and safe fallback remain mandatory at every stage.

## Current status (important)

The existing Step 7.5 implementation:
- captures Minecraft-compiled section geometry;
- mirrors it into bounded CPU and RenderPearl GPU storage;
- builds Radeonium per-layer draw lists and submits copied geometry through an existing Minecraft terrain hook.

It does **not** yet own Minecraft block compilation, terrain shaders, render pipelines, the frame graph or translucent/OIT scheduling. Therefore it is not yet an independent renderer.

Step 8 remains the in-game validation of the current bridge. The roadmap above describes the larger renderer project that follows; it does not mark those future milestones complete.
