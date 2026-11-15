# Radeonium — Full renderer target

## Product goal

Radeonium is intended to grow into an independent Minecraft terrain renderer, not remain a scheduler or a mirror of Minecraft's draw submissions.

The target is a renderer that owns the complete terrain path while preserving Minecraft's visual behavior and supporting the active graphics backend. Performance work must not use lower texture quality, missing lighting, broken transparency, reduced render distance, or disabled visual effects as shortcuts.

A full replacement is a long-term target. The current implementation is only an intermediate bridge and must continue to use safe vanilla fallbacks until each replacement stage is complete and validated.


## Milestone A — renderer contract (implementation)

**Status: complete.**

Milestone A establishes the API boundary before replacing any Minecraft terrain behavior.

### Code delivered

- `RendererContract` defines backend-neutral terrain layers, fallback reasons, world/generation-scoped section build tickets, immutable mesh payloads, opaque GPU mesh handles, frame context and draw descriptors.
- `SectionSnapshot` is the boundary for immutable data captured from Minecraft. It must not expose live world, chunk or mutable game objects to worker threads.
- `SectionMesher` is the CPU-only worker contract.
- `GpuResourceOwner` is the Radeonium GPU allocation/release contract.
- `TerrainPassSubmitter` is the render-thread terrain draw submission contract.
- `VanillaFallback` defines the explicit per-section/per-layer hand-off when Radeonium cannot safely draw its own mesh.
- `requireMatchingResult` rejects a worker mesh whose world epoch or section generation no longer matches the source snapshot.
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
- [x] Worker results are checked against their originating world/section generation.
- [x] The code contract exposes explicit per-layer vanilla fallback.
- [x] Ownership and vanilla fallback rules are documented.
- [x] Automated tests cover the contract invariants.
- [x] GitHub Actions build, JUnit tests and mod JAR packaging complete successfully for the milestone implementation commit (5f205df).

Milestone B must not begin until the final CI item is green. This milestone does not yet implement Minecraft model resolution, a new terrain mesher, GPU uploads, shaders, pipelines or transparency; those belong to later milestones.

## Milestone B — complete Minecraft mesher

**Status: implementation complete with exact per-layer parity gating; vanilla remains the fallback for any unverified layer.**

### Existing foundations

- `MinecraftBlockModelMesher` extracts baked quad positions, packed UVs, face directions and Minecraft render-layer/material references from supplied model parts.
- `MinecraftBlockModelMesh` stores copied quad geometry and can filter by vanilla render layer; each copied quad retains its immutable source `BakedQuad`, effective model-part/material AO, tint index and light-emission metadata.
- `MinecraftMaterialTable` assigns renderer-side material indices while retaining the source Minecraft material metadata for later translation.
- `MinecraftCompiledGeometryStore` now bounds both completed snapshots and in-flight capture tokens; abandoned compiler invocations cannot leave an unbounded capture registry. Copied layer metadata is validated and byte arrays remain defensive copies. Focused tests cover snapshot immutability and invalid metadata.
- The compiled-geometry GPU cache now rejects stale snapshots in both buffer lookup and direct layer-draw entry points, so an invalidated section cannot be drawn through a less common API path.
- `SectionCompilerMixin` captures the real `RenderSectionRegion`: all 4096 block states plus the one-block halo, position-seeded model variants/multipart parts, six neighbor culling decisions, tint sources, packed light, per-vertex AO and emissive light. It also asks Minecraft's own `FluidRenderer` to tessellate fluid states and captures its per-layer vertices, flow UVs, tint and light. Immutable inputs are handed to the bounded `SectionMeshingExecutor` for asynchronous model-quad assembly. The native GPU upload uses Minecraft's exact `DefaultVertexFormat.BLOCK` (28-byte stride) and sequential QUADS indexing, so Radeonium meshes can use the existing correct per-layer Minecraft terrain pipeline without a second shader interpretation. The completed CPU mesh is published with epoch/generation checks, queued to the client tick, and uploaded to Radeonium-owned per-layer GPU buffers. The renderer submits those buffers only when the CPU mesh's encoded quad records match Minecraft's own compiled `MeshData` exactly for that layer; mismatches, stale generations, failed uploads and unsupported content use vanilla fallback. Translucent model/fluid quads are combined and sorted far-to-near per section as the camera moves. Upload/removal queues, CPU snapshots, GPU sections and material slots are bounded. Dirty-section notifications invalidate the section and its six neighbors. The opt-in pass remains disabled by default and requires `-Dradeonium.captureModelMeshes=true`; block-entity/special renderers remain on Minecraft's separate path.
- `MinecraftSectionModelMesher` accepts immutable `ResolvedBlockInput` records and exposes `captureInputs` separately from `buildResolved`; the compiler-thread capture resolves deterministic variant/weighted/multipart parts and copies the one-cell neighbor halo, while `SectionMeshingExecutor.buildMinecraftAsync` performs CPU meshing away from the compiler thread. Context-sensitive/modded model hooks and parity remain unverified.
- `SectionMeshBuilder` contains a greedy opaque-voxel mesher for immutable 16×16×16 material snapshots; it merges coplanar faces of equal material and culls faces against any non-air opaque neighbor.
- The prototype `FluidSectionSnapshot`/`FluidSectionMesher` remains available for isolated algorithm tests. The live Minecraft section path now uses Minecraft's `FluidRenderer` directly, preserving its fluid surface corner rules, side culling, flow-direction UVs, fluid render layer, tint and packed light. Fluid vertices are copied into the same native BLOCK vertex format and included in the parity check; the vanilla terrain shader continues to provide the game's normal atlas sampling, fog and underwater shading.
- `SectionMeshingExecutor` uses a bounded worker queue and reports rejected work instead of running expensive builds on the caller thread.
- `VoxelSectionSnapshot` supports an immutable 18×18×18 one-cell neighbor halo, allowing correct culling at section borders when the caller supplies all six neighboring edge samples.
- `SectionMesh` validates the prototype's fixed 28-byte vertex stride and defensively copies its payload.
- Focused tests cover empty sections, single voxels, greedy merging, shared-face culling between opaque materials, neighbor-halo boundary culling, defensive copies, payload validation and vertex sanity.

### Remaining validation and compatibility work

- Run the opt-in path in a real Minecraft client across vanilla worlds, resource packs, modded block models, biome tint, animated atlas textures, water/lava, glass and section-edge edits. The runtime byte-parity gate is deliberately conservative: any layer that does not exactly match Minecraft's compiled quad records continues to use vanilla.
- Validate model implementations that depend on custom mod hooks, block entities or context outside the captured region. Unsupported or mismatching layers remain on the vanilla path rather than being approximated.
- Verify the translucent centroid sort against Minecraft's in-game sorting for intersecting surfaces and camera movement. If a scene does not match, its layer must remain on vanilla fallback.
- Verify section invalidation and resource/dimension reload behavior under rapid edits and chunk unload/reload. The current neighbor policy immediately removes the six adjacent optional meshes; Minecraft remains responsible for scheduling their actual rebuilds.
- Block-entity and special renderers intentionally remain in Minecraft's separate render path; they are not copied into Radeonium terrain buffers.

The greedy voxel prototype is deliberately not used as a substitute for Minecraft's baked-model semantics. Until every acceptance blocker is closed, vanilla terrain remains authoritative and Radeonium must fall back rather than omit or simplify geometry.

## Milestone C — GPU resource owner

**Status: implementation in progress; CI validation and allocation-reuse coverage remain open.**

### Implemented foundation

- Adds `MinecraftGpuResourceOwner` as the single owner for Radeonium-created terrain vertex uploads.
- Routes section-cache allocations, translucent re-uploads, replacement and eviction through that owner.
- Tracks active upload count and allocated GPU bytes by upload identity.
- Reuses identical immutable section-layer vertex payloads through content keys and reference-counted GPU allocations.
- Releases a shared allocation only after its final section reference is removed.
- Rejects attempts to release a live upload not owned by this manager and makes repeated release safe.
- Provides a render-thread-only bulk release path and deterministic shutdown.
- Keeps Minecraft-owned buffers outside Radeonium's ownership boundary.

### Remaining acceptance work

- Unit tests cover empty-owner accounting, immutable content-key equality and defensive copying, reference-count transitions/underflow, closed and duplicate upload rejection, byte-accounting overflow cleanup, external-close reconciliation, and retryable disposal/shutdown failures. A controlled upload fixture and injectable render-thread guard exercise owner lifecycle behavior without allocating a real GPU buffer.
- Section rebuilds prepare replacement uploads and material references before committing the new cache entry. Failures during preparation release only uncommitted uploads; cleanup errors are attached to the original failure instead of masking it.
- Cache removal, eviction and clear keep entries reachable until their GPU/material references have been released. Failed disposal remains retryable, and partially disposed uploads are excluded from draw lookup so vanilla can remain the fallback.
- Material retain/release transitions validate all affected slots before mutating any reference counts, preventing partial updates on overflow or underflow.
- Remaining: add cache-level integration coverage for successful device allocation/reuse, replacement, eviction and cache clear, plus an in-game lifecycle check against the active graphics backend.
- Run the Gradle build and JUnit suite only after implementation and test authoring are complete; then confirm CI compilation, JUnit tests and JAR packaging.

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

The existing Step 7.5 bridge still captures Minecraft-compiled geometry as a safe fallback. The opt-in Milestone B path separately captures and meshes Minecraft model/fluid inputs, compares its encoded per-layer quads against Minecraft's compiled bytes, and submits only verified Radeonium-owned buffers through the existing terrain hook. It does **not** yet own Minecraft block compilation, terrain shaders, render pipelines, the frame graph or the complete transparency system. Therefore it is not yet an independent renderer.

The in-game validation matrix remains required before enabling the opt-in path by default. The roadmap above describes the larger renderer project that follows; Milestone B completion does not mark those future milestones complete.
