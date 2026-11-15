# Minecraft mesher validation

The Radeonium model/fluid mesher is opt-in. Add this JVM argument to a local
Minecraft test profile:

```text
-Dradeonium.captureModelMeshes=true
```

The feature remains disabled by default. Minecraft continues compiling its
normal section mesh. Radeonium captures the same section inputs, creates its
own layer buffers, and compares the resulting native `DefaultVertexFormat.BLOCK`
quad records with Minecraft's compiled `MeshData`.

## Runtime safety gate

A Radeonium layer is submitted only when all of the following are true:

- the Radeonium capture is still the current section generation;
- the vanilla compile snapshot for that section is available;
- vertex format and primitive topology match Minecraft's terrain pipeline;
- vertex counts match;
- every quad's ordered four-vertex byte record matches the vanilla output.

The comparison ignores ordering between quads, but preserves vertex order inside
each quad. This checks winding, positions, atlas UVs, tint/AO color and packed
light. Translucent quads are sorted far-to-near per section using the current
camera position; a new GPU upload is made only when the sorted order changes.

If any check fails, that layer uses Minecraft's normal geometry. A missing or
stale Radeonium buffer also falls back to vanilla. Block-entity renderers and
special renderers remain on Minecraft's separate path.

## Manual test matrix

Run the opt-in path in a local client and inspect:

1. Opaque blocks with full cubes, partial shapes and adjacent same-block faces.
2. Cutout blocks, including leaves, plants and fences.
3. Biome-tinted blocks and blocks with ambient occlusion.
4. Emissive blocks and blocks beside changing light sources.
5. Animated atlas textures and resource-pack model variants.
6. Water, lava, glass and intersecting translucent surfaces while moving the camera.
7. Block edits on section boundaries, chunk unload/reload and dimension changes.
8. Resource-pack reloads while sections are visible.
9. Modded blocks with custom model or block-entity render hooks.

Watch the client log for per-section parity fallback diagnostics. A fallback is
intentional: it means Radeonium did not prove that layer equivalent to vanilla.
Do not disable the parity gate to hide a mismatch. Report the affected block,
resource pack/mod set and layer instead.

To restore the default path, remove the JVM argument and restart the client.
