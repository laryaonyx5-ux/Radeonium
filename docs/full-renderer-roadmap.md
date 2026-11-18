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
- Async capture tickets expose a synchronized `isCurrent` generation/epoch check. The meshing executor checks this predicate before beginning the expensive CPU build and again before publishing, so superseded, removed, aborted or world-reload captures are rejected before consuming worker time whenever they have not started yet; focused tests cover each invalidation path.
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

**Status: implementation complete; Gradle build, JUnit tests and mod JAR packaging green; in-game device validation pending.**

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

- Unit tests cover empty-owner accounting, immutable content-key equality and defensive copying, reference-count transitions/underflow, content-key reuse and cache misses, closed and duplicate upload rejection, byte-accounting overflow cleanup, external-close reconciliation, retryable disposal/shutdown failures, and bulk-release recovery while keeping the owner reusable. A controlled upload fixture and injectable render-thread guard exercise owner lifecycle behavior without allocating a real GPU buffer.
- Section rebuilds prepare replacement uploads and material references before committing the new cache entry. Failures during preparation release only uncommitted uploads; cleanup errors are attached to the original failure instead of masking it.
- Cache removal, eviction and clear keep entries reachable until their GPU/material references have been released. Failed disposal remains retryable, and partially disposed uploads are excluded from draw lookup so vanilla can remain the fallback.
- Material retain/release transitions validate all affected slots before mutating any reference counts, preventing partial updates on overflow or underflow.
- Cache lifecycle tests exercise empty-section replacement, removal, LRU and byte-budget eviction, GPU-upload replacement, shared uploads across sections, failed replacement rollback, retryable remove/clear disposal, and shutdown using controlled upload fixtures and an injected render-thread guard.
- Automated owner tests cover content-key cache hits/misses, reference sharing, accounting overflow, foreign/closed upload rejection, bulk release recovery, and retryable disposal. Cache commits reject foreign or closed uploads before replacing a valid entry. Actual device allocation and disposal still require an in-game check against the active graphics backend.
- The configured GitHub Actions `clean build` completed successfully for commit `ca2a5f5`; compilation, JUnit tests and mod JAR artifact packaging all passed.

## Milestone D — terrain shader and material system

**Status: shader/material implementation complete; active pipeline integration is covered by partial Step 8; runtime visual parity validation pending.**

### Implemented foundation

- Replaces the placeholder shader inputs with a vertex contract matching the current 28-byte Minecraft BLOCK upload: position, normalized vertex color, atlas UV and packed block/sky light.
- The vertex shader transforms section vertices and forwards UV, vertex tint/AO, lightmap coordinates and view-space fog distance.
- The fragment shader samples the block atlas and lightmap, applies captured vertex color, supports configurable alpha cutout and fog, and preserves sampled alpha for the layer-specific blend state.
- Adds `TerrainShaderContract` as the authoritative resource, attribute-location, byte-offset, stride and uniform-name contract for later pipeline creation.
- Adds immutable `TerrainMaterialBinding` plans that classify Minecraft SOLID, CUTOUT, CUTOUT_MIPPED and TRANSLUCENT layers as opaque, cutout or blend materials.
- Exposes shader-facing binding snapshots from `MinecraftMaterialTable` while retaining the original Minecraft `MaterialInfo`; no guessed sprite coordinates or atlas handles are fabricated.
- JUnit tests validate shader resource presence and required inputs/uniforms, vertex-layout agreement, material validation and layer-to-alpha-mode mapping.

### Deliberate boundary

This milestone supplies shader sources and material-binding contracts. Active SOLID/CUTOUT pipeline selection is now implemented in the Step 8 shader-activation follow-up. The live RenderPearl shader compiler and in-game visual parity still require validation; until that validation passes, vanilla remains the authoritative fallback.

## Milestone E — independent terrain pipelines

**Status: Radeonium SOLID/CUTOUT pipelines and custom shader selection implemented; runtime pipeline compilation and visual validation pending.**

### Implemented foundation

- Adds two distinct Radeonium-owned RenderPearl pipeline identities for SOLID and CUTOUT terrain layers, each with a Radeonium pipeline location and Minecraft's terrain bind-group/vertex-format/render-state snippet.
- Uses a dedicated alpha-cutout shader define for CUTOUT while retaining the same native terrain vertex contract.
- Routes opaque and cutout terrain submissions through Radeonium's pipeline identities in the existing per-layer draw hook.
- Keeps TRANSLUCENT on the host-provided OIT override or Minecraft's vanilla translucent pipeline. Radeonium's full translucent replacement remains Milestone F.
- Adds focused tests for pipeline identity separation, layer routing, override policy and null handling.
- The section draw list and batching remain Radeonium-built; no second submission is added, so the same terrain layer is not intentionally drawn twice.

### Deliberate compatibility boundary

The current pipeline objects have Radeonium identities and are used for SOLID/CUTOUT submissions. The Step 8 follow-up now selects Radeonium's own vertex and fragment stages while retaining Minecraft's compatible terrain bind-group layout, vertex format and fixed render state. Translucent OIT remains host-owned; no independent transparency replacement is claimed.

Runtime RenderPearl compilation, visual comparison and backend checks remain pending. If a pipeline cannot compile or render correctly, the corresponding vanilla path must remain available.

### Shader activation follow-up — partial Step 8

**Status: Radeonium shader stages are now assigned to the active SOLID/CUTOUT RenderPearl pipelines; runtime shader compilation and visual parity remain unverified.**

- The SOLID and CUTOUT pipeline builders now select `radeonium:core/terrain` for both vertex and fragment stages instead of inheriting Minecraft's terrain shader stages.
- The custom shader pair now consumes the Minecraft 26.3 multi-draw terrain interface: BLOCK vertex attributes, conditional per-draw section position/visibility, atlas/lightmap samplers, fog and projection uniforms.
- The fragment stage preserves Minecraft's RGSS/nearest texture filtering, cutout discard, section visibility fade and OIT alpha-only/accumulation hooks. OIT remains a host-owned frame-graph feature for translucent draws; these hooks preserve interface compatibility rather than claiming a new Radeonium OIT implementation.
- CUTOUT retains its dedicated alpha threshold define; SOLID does not discard alpha.
- The vanilla terrain snippet remains in place only for compatible bind-group layouts, vertex format, depth/color state and render-pass integration. This is a shader replacement, not yet a fully independent backend/pipeline state or OIT implementation.
- Focused tests assert the shader resources expose the expected 26.3 inputs, outputs, includes and uniforms. CI compilation, JUnit tests and JAR packaging pass.
- The active RenderPearl runtime shader compiler and in-game image parity must still be checked. If shader compilation or visuals fail, the vanilla fallback must remain available.

## Milestone F — translucent terrain ordering and blending

**Status: ordering implementation complete; independent blending/OIT replacement and in-game validation pending.**

### Implemented foundation

- Keeps translucent terrain on the frame graph's supplied host OIT pipeline when one is provided, with Minecraft's translucent pipeline as the compatibility fallback. Radeonium does not silently replace a working host transparency path with an unverified blend state.
- Sorts Radeonium-owned model and fluid quads inside each section upload from far to near as the camera moves.
- Adds a second deterministic far-to-near ordering layer across visible translucent section draws, using section-center distance and stable input-order tie breaking. This replaces the previous unconditional list reversal.
- Adds focused tests for per-quad ordering, global section ordering, stable ties and invalid inputs.
- Retains the per-layer parity gate and vanilla buffer fallback: stale, unsupported or incompatible section geometry is never dropped.

### Deliberate compatibility boundary

Section-center ordering is an approximation for ordinary alpha blending; it cannot correctly order intersecting translucent surfaces across section boundaries. Water, glass and modded translucent geometry therefore continue to use the host OIT override when available, or Minecraft's vanilla translucent path otherwise. Radeonium does not yet own a replacement OIT implementation or its backend-specific depth/blend resources. Runtime visual checks for water, glass, ice, intersecting surfaces and camera movement remain required before any host transparency path can be replaced.

## Milestone G — visibility and section lifecycle

**Status: implementation and focused lifecycle tests complete; in-game unload/reload and visibility validation pending.**

### Implemented lifecycle safeguards

- Keeps section build generations globally monotonic for the lifetime of the renderer pipeline. Clearing a world, removing a section and later recreating the same coordinates can no longer reuse an old generation token (the stale-worker ABA case).
- Dirty-section notifications invalidate the changed section and all six face-neighbors in the renderer pipeline.
- The same invalidation now removes each affected optional model snapshot before queuing its render-thread GPU-cache removal. This prevents the removal queue from retaining a stale GPU mesh merely because the old CPU snapshot still exists.
- Existing world-identity tracking clears section CPU/GPU state, pending uploads and capture generations when the client changes level or unloads the world.
- Existing resource reload handling clears baked-model snapshots and pending model uploads/removals, then schedules render-thread GPU-cache invalidation.
- Visible-section reconciliation continues to use Minecraft's visible set as the authority; Radeonium does not invent a competing frustum/occlusion result.
- Focused tests cover generation invalidation across world clear/recreation, section removal/recreation, repeated dirty notifications and visible-set reconciliation.

### Remaining acceptance work

- In-game stress test rapid block edits at section borders and confirm the changed section plus six neighbors rebuild without stale faces or seams.
- Travel repeatedly across chunk boundaries and verify unload/reload does not leave missing or stale geometry.
- Change dimensions and reload resource packs while asynchronous section jobs are active; confirm old jobs cannot publish into the new world/resource epoch.
- Compare Radeonium's visible draw list with Minecraft under camera rotation, occlusion and frustum movement; confirm invisible sections do not appear and returning sections rebuild correctly.

Vanilla remains the compatibility authority for unsupported or stale geometry. No independent Radeonium occlusion system or performance gain is claimed by this milestone.

## Milestone I — render diagnostics and fallback accounting (Step 9)

**Status: implementation and focused tests complete; in-game counter inspection and visual validation belong to Step 10.**

### Implemented

- Adds thread-safe cumulative counters for terrain draws sourced from Radeonium's model mesh cache, Radeonium's copied compiled-geometry cache, or Minecraft's vanilla fallback.
- Tracks counters independently for SOLID, CUTOUT and TRANSLUCENT layers.
- Counts visible sections that currently have no compiled mesh, separately from submitted draws.
- Exposes immutable snapshots with per-layer and total counts through `RadeoniumClient.terrainRenderDiagnostics()`.
- Provides an explicit reset operation and resets counters during client shutdown.
- Records the source at the point the visible terrain draw is selected, so the counters report actual submitted draw-list choices rather than estimates of GPU work.
- Uses `LongAdder` counters to support safe reads and recording without serializing worker/render diagnostics.
- Focused tests cover per-layer source accounting, immutable snapshots, reset behavior, invalid inputs and concurrent updates.

### Deliberate boundary

These counters do not measure frame time, GPU execution, bytes transferred or FPS. They are observability for draw ownership and fallback only. They do not change draw selection, bypass compatibility checks or disable vanilla fallback.

### Step 10 validation checklist

- Inspect snapshots in a live client while viewing ordinary opaque terrain, cutout foliage, water/glass and unsupported/modded blocks.
- Confirm Radeonium counters increase only for the owned buffers actually selected and vanilla fallback counters increase when compatibility checks reject an upload.
- Confirm no-mesh counts fall as visible sections compile, and no stale/missing terrain appears during movement, edits, unload/reload or resource-pack changes.
- Run the visual/backend/resource-pack/mod compatibility matrix and collect frame-time/memory measurements separately; no performance claim follows from draw counters alone.

## Step 12 — advanced section culling

**Status: implementation, automated tests and GitHub Actions build/test/JAR packaging complete; live-client visual and performance validation pending.**

- `SectionVisibilityGraph` stores the full 6×6 entry-face/exit-face connectivity matrix rather than reducing visibility to a lossy six-bit open-face mask.
- `RendererSectionTracker` reads the actual `SectionMesh.facesCanSeeEachother` results for each visible compiled section and updates the graph after extraction. Unknown or unavailable mesh visibility is treated as fully connected, which fails open instead of hiding terrain.
- `RadeoniumChunkSectionsToRender` derives the camera section and filters its draw-list input through the portal graph. It first verifies that every current Minecraft-visible section has a graph entry; if the camera section or any section's visibility data is missing, it keeps the entire Minecraft list unchanged.
- The source list remains Minecraft's frustum-filtered visible set. Radeonium now applies its own face-connectivity traversal on that set; it does not claim an independent frustum-plane extraction or replace Minecraft's octree/frustum stage.
- The standalone six-plane positive-vertex AABB culler and in-place filtering tests remain available for a future Radeonium-owned frustum feed.
- Tests cover plane validation, boundary behavior, face-pair connectivity, graph coverage and fail-open fallback. GitHub Actions run 43761837413 passed compilation, all 133 JUnit tests and mod JAR packaging. No FPS improvement is claimed until measured in a live client.

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
| D — terrain shader/material system | Define Radeonium terrain shader sources and material bindings | Shader contract/tests complete; runtime visual parity for opaque/cutout terrain, tint, light, fog and emissive content pending |
| E — independent terrain pipelines | Radeonium owns terrain pipeline identities, opaque/cutout state selection and draw routing | Radeonium pipeline compilation and opaque/cutout visual checks pass; shader-bind-group reconciliation remains explicit |
| F — transparency renderer | Radeonium owns translucent terrain ordering/blending | Water, glass and intersecting surfaces pass visual tests |
| G — visibility and lifecycle | Radeonium owns or explicitly integrates visibility, invalidation and reload handling | No missing/stale geometry under movement and world changes |
| H — full renderer validation | Exercise supported backends, packs, mods and performance scenarios | Correctness and benchmark report; only then consider vanilla terrain fallback optional |

The order may be adjusted when implementation dependencies require it, but visual correctness and safe fallback remain mandatory at every stage.

## Current status (important)

The existing Step 7.5 bridge still captures Minecraft-compiled geometry as a safe fallback. The opt-in Milestone B path separately captures and meshes Minecraft model/fluid inputs, compares its encoded per-layer quads against Minecraft's compiled bytes, and submits only verified Radeonium-owned buffers through the existing terrain hook. Step 8 now binds Radeonium-owned shader stages to the SOLID/CUTOUT pipeline identities while retaining Minecraft's compatible terrain bind groups and fixed state. It does **not** yet own Minecraft block compilation, the frame graph or the complete transparency system; translucent OIT may still use a host override. Therefore it is not yet a fully independent renderer.

The in-game validation matrix remains required before enabling the opt-in path by default. The roadmap above describes the larger renderer project that follows; Milestone B completion does not mark those future milestones complete.

## Step 13 — terrain draw batching

**Status: code, automated tests and GitHub Actions build/test/JAR packaging complete; live-client performance validation pending.**

- Added `TerrainDrawBatchMetrics`, which measures per-frame draw entries, unique vertex-buffer identities and contiguous buffer-bind runs per terrain layer.
- Added stable identity-based grouping for SOLID and CUTOUT submissions. Draws using the same exact vertex-buffer handle are placed together, preserving their relative order within each group.
- Each draw retains its own section uniform, index range and geometry. This implementation reduces avoidable vertex-buffer switches where handles are shared; it does not merge draw calls or GPU allocations.
- TRANSLUCENT submissions are deliberately excluded from regrouping so their blending order is preserved.
- Metrics are connected to the live draw-list construction and report the final per-layer submission order.
- Tests cover grouping stability, identity rather than equality, null handles, invalid input, per-layer metrics and frame resets.
- No FPS gain or actual draw-call reduction is claimed until measured in a live client. If visible opaque/cutout ordering issues appear, the grouping must be disabled or narrowed.

## Step 14 — GPU optimizations

**Status: implementation and focused tests complete; GitHub Actions validation pending; live-device profiling remains pending.**

- Added `TerrainGpuUploadMetrics` to measure compiled-geometry upload attempts, payload bytes, successful uploads, stale-result skips, failures, cumulative CPU-side upload duration and maximum successful upload duration.
- Connected the metrics to the render-thread upload queue and exposed a read-only snapshot accessor. Stale snapshots are counted separately from real failures.
- Added `TerrainGpuUploadBudget` and replaced the fixed four-upload loop with a bounded scheduler: at most four sections, 8 MiB of queued payload and 2 ms of CPU wall time per client tick. The first item is always allowed, so one oversized section cannot starve indefinitely; later items remain queued for a following tick when a limit is reached.
- Uploads remain on the render thread. Existing bounded queue capacity, 64 MiB GPU cache limit, stale-snapshot checks, per-layer allocation cleanup and vanilla fallback are retained.
- Added focused tests for metrics aggregation/reset/failure/skip accounting and scheduler count/byte/time limits, including oversized-first-item and invalid-input cases.
- The measured duration is CPU-side wall time around the upload call, not GPU execution time. These limits are conservative starting values, not device-specific tuning; live profiling is still required before claiming an FPS improvement.

## Step 15 — multithreaded terrain preparation

**Status: implementation and focused tests complete; GitHub Actions validation pending; live workload profiling pending.**

- The existing bounded CPU meshing pool remains the only worker path for immutable section inputs; workers never access the live Minecraft world or GPU resources.
- Added `SectionMeshingMetrics` to record accepted jobs, completed builds, build failures, rejected submissions, queue-wait duration and worker build duration (including maximum and average build time).
- Instrumented both prototype voxel builds and Minecraft section-model builds. Rejected submissions remain exceptional futures and are not silently moved onto the caller/render thread.
- Exposed worker-pool metrics and worker-count information for diagnostics.
- Added focused tests for aggregation, failures versus queue rejections, reset and invalid timing values.
- Added stale-snapshot predicates to both meshing entry points. The predicate is checked before work starts and again after the CPU build, so obsolete results are never published; callers can cancel queued futures, which removes those jobs from the bounded queue when they have not started.
- Added cancellation telemetry, queue-capacity visibility and explicit closed-state reporting. Submissions after shutdown are rejected immediately rather than being accepted into a dead pool.
- Shutdown now stops admission, allows a short graceful drain, then interrupts and completes abandoned queued jobs as cancelled. Repeated close calls are safe; GPU upload and resource disposal remain render-thread-owned.
- Added focused tests for stale-result rejection, cancellation accounting, closed-pool rejection and idempotent shutdown, alongside the first-half worker metrics tests.
- The executor accepts only immutable captures and a thread-safe validity predicate; it never reads the live world from a worker. Queue capacity remains bounded at 256.
- These counters measure CPU-side queue/build time, not GPU time or FPS. No performance gain is claimed until measured in a live Minecraft workload.


## Step 16 — Radeonium-owned translucent terrain OIT

**Status: OIT pipeline ownership, stage routing, third-party override protection, focused tests and GitHub Actions build/test/JAR packaging complete; live-client visual validation pending.**

- Adds a Radeonium-owned OIT pipeline set for the three terrain transparency stages: depth bounds, transmittance and accumulation.
- Builds the stage set from Minecraft's multi-draw terrain interface while selecting Radeonium's own terrain vertex and fragment shaders. The existing shader implements the alpha-only passes and accumulation sampling hooks.
- Translates the vanilla terrain OIT stage pipeline identities to the corresponding Radeonium-owned stage pipelines at the terrain submission boundary. Both separate and multi-draw vanilla OIT sets are recognized.
- Preserves unknown third-party pipeline overrides even when the other override slot contains a recognized vanilla OIT stage, and retains Minecraft's classic non-OIT translucent pipeline as the compatibility fallback. No unverified classic blend-state replacement is forced.
- Adds focused tests for distinct Radeonium OIT stage identities, both vanilla OIT stage mappings, compatibility fallback, third-party override precedence and opaque/cutout routing.
- This is pipeline ownership, not proof of correct OIT shader compilation or visual parity. In-game checks for water, glass, ice, intersecting translucent surfaces, camera movement, resource packs and graphics backends remain mandatory. No FPS gain is claimed.


## Step 17 — independent section compilation (active work)

**Current implementation checkpoint: model/fluid mesh generation exists, but it is still a parallel capture path. It is not yet an authoritative replacement for Minecraft's SectionCompiler.**

- Section input capture now separately counts non-air blocks whose render shape is not MODEL, in addition to block entities. Those blocks are not silently treated as if their geometry had been included in Radeonium's model mesh.
- The unsupported-render-shape count is carried through the immutable worker input and resulting section mesh, with validation against negative metadata and focused unit coverage.
- This is a completeness/safety prerequisite only. It does not yet route those special render paths through Radeonium, construct Minecraft's complete compiler result, or bypass the vanilla compiler.

- Follow-up: unsupported block positions, render-shape classification and block-entity presence are now preserved as immutable metadata all the way from compiler-thread capture through the asynchronous Radeonium section mesh. This gives the future authoritative compiler a concrete per-block fallback manifest instead of only aggregate counters. It remains metadata for fallback planning; it does not yet invoke Minecraft's block-entity renderer or replace the vanilla compiler.
