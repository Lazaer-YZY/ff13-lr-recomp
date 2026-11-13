# Shaders and pipelines: how they are built, and how we remove runtime compile stutter

Status: 2026-11-09 (built and measured on the Thor; see sections 2b-4). Target: Android arm64 (AYN Thor, Adreno 740). Code paths are in the SDK fork `sdk/rexglue-sdk` (exported as `patches/rexglue-sdk/`).

## 1. Survey: how ReXGlue handles Xenos shaders and Vulkan pipelines

### Shader translation (runtime, Xenia design)
- First sight: the PM4 "load shader instruction memory" packet (`src/graphics/command_processor.cpp` ~1530) calls `VulkanPipelineCache::LoadShader`: XXH3 of the uploaded ucode dwords -> `shaders_` map. Nothing is translated yet.
- At draw time (`VulkanCommandProcessor::IssueDraw`): `AnalyzeUcode`, then `GetCurrentVertexShaderModification` / `GetCurrentPixelShaderModification`, `GetOrCreateTranslation(modification)` and `EnsureShadersTranslated` -> `SpirvShaderTranslator` -> `VkShaderModule`, synchronously on the GPU command thread (~0.75 ms per translation on the Thor).
- What a translation bakes in (`SpirvShaderTranslator::Modification`, 64 bit, `kVersion` 12):
  - vertex: interpolator mask (VS writes & PS reads), point-size/param-gen output, dynamic addressable register count, user clip planes (count, cull), vertex kill mode, point PS UCP mode, host vertex shader type (vertex / point-list-as-strip / rect-list-as-strip / tessellation domain), tessellation mode;
  - pixel: interpolator mask, centroid mask, dynamic addressable register count, param gen (enable, interpolator, point), depth/stencil mode (early-Z hint, float24 truncating/rounding).
  - NOT baked (already uniforms/system constants): alpha test (function + reference), alpha-to-mask, render target formats and gamma (EDRAM flags), texture signedness and swizzles, colour exponent bias, stencil reference/masks, polygon offset.
  - Vertex fetch is done in the shader from the shared-memory storage buffer; formats come from the fetch instructions (shader alone), address/stride/endian from fetch constants at runtime. Pipelines have no vertex input state.
- Draw-state-dependent parts of the modification: interpolator masks (VS/PS pairing), centroid sampling (MSAA mode), param gen, host VS type (primitive type), clip planes, float24 depth mode.

### Pipelines
- Key: `PipelineDescription` (`include/rex/graphics/vulkan/pipeline_cache.h`, `kVersion` 0x20260228): VS hash + modification, PS hash + modification, render pass key (RT formats, sample count), geometry shader kind, primitive topology, primitive restart, tessellation mode, depth clamp, polygon mode, cull front/back, front face, rasterizer discard, depth write/compare, stencil enable/ops/compare (front/back), sample-rate shading, 4x {blend factors/ops, write mask}.
- Created in `VulkanPipelineCache::EnsurePipelineCreated` with `vkCreateGraphicsPipelines` and the device-wide `VkPipelineCache`. Dynamic state today: viewport, scissor, depth bias, blend constants, stencil compare/write mask, stencil reference. Everything else above is baked. Dynamic rendering (`VkPipelineRenderingCreateInfo`) when supported. No VK_EXT_extended_dynamic_state*, no graphics pipeline library, no specialization constants.

### Persistent storage (Xenia "shader storage", already working)
- `cache/shaders/shareable/5351081B.xsh`: guest ucode records (hash, size, type, dwords) of every shader the game loaded.
- `5351081B.fbo.vk.xpso`: every `PipelineDescription` ever created (66-byte records).
- `5351081B.vk.pipelinecache`: the driver's `VkPipelineCache` blob (device + driver build specific), merged at the start of the command processor setup (so the built-in resolve/transfer/texture-load pipelines hit it too) and saved by the storage writer thread.
- At startup (`InitializeShaderStorage`) every stored description is translated (6 threads, ~200 ms for 266 translations) and created (6 threads; ~45 ms for 313 pipelines from the driver cache, ~9 s cold on Turnip).
- Tools: `tools/shader_cache.sh pull|push`, `tools/merge_shader_cache.py`.

### Runtime misses before this work
- `async_shader_compilation` (default on) created a placeholder pipeline (same state, trivial PS) synchronously on the GPU thread, queued the real one, skipped the draw and held the frame (`vulkan_async_skip_incomplete_frames`).
- Draws without a pixel shader always compiled synchronously.
- At the end of every submission the GPU thread drained the creation queue itself and waited for the creation threads (`EndSubmission`), a Direct3D 12 heritage (there a recorded command list needs its pipeline objects). On the Thor that turned every compile burst into a GPU-thread stall: ~170 ms of CPU per pipeline (Turnip and Qualcomm alike), so 20 new pipelines = ~0.6 s.

### Driver capabilities on the Thor (from the runtime's device log)
| Extension | Qualcomm v819.2 | Turnip (Mesa 26.0 / 26.2) |
|---|---|---|
| VK_EXT_extended_dynamic_state, _2 | yes | yes |
| VK_EXT_extended_dynamic_state3 | no | yes |
| VK_EXT_vertex_input_dynamic_state | yes | yes |
| VK_EXT_graphics_pipeline_library (+ KHR_pipeline_library) | no | yes |
| VK_KHR_maintenance5, VK_EXT_descriptor_buffer | no | yes |
| VK_EXT_shader_object | no | no |
Qualcomm v819.2 also renders Luxerion interiors as an empty void (a driver bug, 2026-11-08), so Turnip is the default driver; the Qualcomm path is a fallback.

### Prior art
- UnleashedRecomp (Sonic): XenosRecomp translates every shader container found in the game files at build time (scan for `(flags & 0xFFFFFF00) == 0x102A1100`, 9 big-endian u32 header, dedupe by XXH3) into a hash-sorted SPIR-V/DXIL bundle; pipelines are compiled when assets load (hooks on model/material loading, the game's loading screen waits for them) plus a shipped list of recorded pipeline states; specialization constants for a few toggles; no pipeline libraries.
- Lighthing-Recomp (FFXIII, same runtime family): no archive or shader tooling; runtime translation only.
- lr-ffxiii-port: the same storage files; `converters/jpvoice/lr_jpvoice.cpp` decrypts and reads LR's filelists and white_img archives (360 build) - reused for our scanner.
- Surihix WhiteBinTools: filelist crypt documented for the PC builds.

## 2. Design

1. **Never compile on the GPU thread** (done, SDK patch 71): a pipeline missing at draw time is queued for the background creation threads only; its draws are skipped until it is published; no placeholder pipeline; no drain/wait at the end of a submission (`vulkan_pipeline_compile_never_blocks`, default on). Counters every 10 s in the log: `Pipelines (10 s): N queued ..., M created synchronously on the GPU thread (ms), K shader translations on the GPU thread (ms), D draws skipped while pending, H of F frames held`.
2. **Offline shader extraction** (`tools/lr_shader_scan.py`): decrypt + parse every filelist, inflate every archive entry, scan for Xenos shader containers, dedupe by the ucode hash the runtime uses, cross-check against the runtime's own `.xsh`, translate every shader with the host translator and report failures; ship the set as a shader storage file so the runtime knows every shader from the first boot.
3. **Graphics pipeline library on Turnip**: compile per-shader libraries (pre-rasterization from VS + modification, fragment from PS + modification) ahead of time from the shipped shader set, make the remaining fixed-function state dynamic (EDS1/2/3: cull, front face, topology class, depth/stencil, polygon mode, depth clamp, rasterizer discard, colour blend/write mask), and link pipelines at draw time (fast link, no LTO) - no per-state compile. Qualcomm (no GPL, no EDS3) keeps full pipelines + the stored list + the non-blocking queue.
4. **Harvest**: pipeline descriptions seen at runtime are appended to the `.xpso` (already) and can be merged from any device (`merge_shader_cache.py`); an automated area-load harvest is investigated in section 4.

## 2b. What was built (2026-11-09)

1. **Never block** (SDK patch 71): misses are pending entries compiled by the creation threads only; no placeholder pipelines; no end-of-submission drain. `vulkan_pipeline_compile_never_blocks`.
2. **Device plumbing** (SDK patch 73): extended dynamic state 1/2 (core 1.3) and 3, graphics pipeline library + pipeline library extensions, deferred commands for all the dynamic state.
3. **Pairing-independent translations** (SDK patch 73, active with libraries): the vertex shader exports every interpolator it writes, the pixel shader reads all of its inputs (the pair's intersection only when the vertex shader doesn't cover the pixel shader); interpolator I at location I in every stage (and in the generated geometry shaders, whose key now holds the interpolator mask).
4. **Pipelines linked from libraries** (SDK patch 74, `vulkan_pipeline_libraries`, on when the device has GPL with fast linking, EDS1/2/3 and dynamic rendering - Turnip; the Qualcomm driver keeps whole pipelines):
   - vertex input (topology, primitive restart), pre-rasterization (vertex shader + expansion geometry shader; cull, front face, polygon mode, depth clamp, rasterizer discard, depth bias enable dynamic), fragment shader (pixel shader or depth-only helper; depth/stencil test, write, compare and ops dynamic; sample count fixed), fragment output (formats; blend enable/equation/write mask dynamic);
   - layouts with independent sets; the per-stage library layouts are shared by every pair using the same texture set;
   - the bound pipeline's fixed-function values are set as dynamic state after binding (tracked, re-sent after a pipeline with static state or a new command buffer);
   - library keys are stable across runs (shader ucode hash + modification, geometry shader key, sample count/mask/shading, attachment formats), and every library created is appended to `5351081B.vk.libkeys` (with the driver's pipeline cache UUID), so a later start knows which libraries the driver cache produces quickly.
5. **Linked on the spot** (SDK patch 74): a missing pipeline whose libraries all exist or are known to be in the driver cache is linked synchronously on the GPU thread (a fast link, no pending draws, no held frame); otherwise it goes to the creation threads.
6. **First-boot precompile** (SDK patch 74): `tools/lr_shader_scan.py` also writes `5351081B.psmeta` (per pixel shader: the shader header word with the screen-position / parameter generation register). At startup a background thread ("Vulkan Library Precompile", nice 10, not on the little cores) builds a fragment library for every stored pixel shader with the canonical modification the draws will request (interpolators 0..n-1 minus the parameter generation register, early-Z hint when allowed plus the alpha-tested variant), for 1x and 4x MSAA; a marker file (`5351081B.vk.libprecompiled`: driver UUID + shader count) skips it on later starts.

## 3. Results
(updated as the work lands)

### Offline extraction (`tools/lr_shader_scan.py`, 2026-11-08)
- Run: `python -I tools/lr_shader_scan.py "C:/lr/Input - Data/extracted" OUT --image IMAGE.bin --compare RUNTIME.xsh` (29 s). The executable image comes from `rexglue codegen <copy of lr_manifest.toml> --dump-image IMAGE.bin` (run on a throwaway copy of the manifest with absolute paths and the `analysis/` folder; stop it after the dump, it would otherwise regenerate code). XXH3 via `tools/native/xxh3.dll` (`tools/native/build_xxh3.sh`, the SDK's bundled xxHash).
- Filelists: all three sys filelists decrypt (XOR-table cipher, checksum verified) and four zone filelists are found inside `white_img2u` (`zone/filelist_z0100u..z0121u`); 38,206 archive entries, 0 unreadable.
- Shader containers: 37,963 in the archives (`white_imgu` 5,909, `white_img2u` 5,774, zone z0100 26,076, z0110 204; z0120/z0121 and `white_scru` none) + 325 in the executable image; 67 candidates rejected (non-zero reserved header fields). **7,590 unique shaders: 215 vertex, 7,375 pixel.**
- Against the runtime's own storage after the prologue + a Luxerion interior (279 shaders): **every pixel shader found** (158 of 279 overall); the 121 missing are all vertex shaders, 50 of them within 12 dwords of an offline shader of the same length. The 360's Direct3D runtime patches the vertex fetch fields of a vertex shader when it is bound to a vertex declaration, so the uploaded microcode (what the runtime hashes) is a per-declaration variant of the container's.
- Translation report (`--vulkan_storage_translate_all`, the runtime's own SPIR-V translator, default modifications: vertex shaders exporting every interpolator they write, pixel shaders reading all 16): **7,711 shaders (offline set + the 121 runtime vertex variants), 7,711 translated, 0 failed**, 10.3 s on 6 threads of the Thor.
- The merged set is in the Thor's storage (`tools/shader_cache.sh push OUT`); reading 7,711 shaders at startup costs 23 ms. The offline set contains game code: it is generated from the user's own disc and never committed.

| Measurement | Before | After |
|---|---|---|
| Cache-off boot to the title (all pipelines miss), GPU-thread compiles | (placeholder + drain per submission) | 0 synchronous, 21 queued, 9 of 293 frames held in the first 10 s, game frames over 45 ms 6 then 0-2 per 10 s |
| Luxerion play, 7 min (libraries, before the precompile) | 852 compiles during play, 16.3% slow frames (cache broken) / 3.7% (Turnip, whole pipelines) | 0 synchronous, 75 queued, 58 frames held, 2.6% slow frames |
| First boot, pixel shader library precompile (Thor, Stable Turnip v2) | - | 8,411 libraries in 312 s in the background (title kept 300 of 300 frames); driver cache 334 MB |
| Warm boot | 9.6 s cold / 0.6 s (whole pipelines) | driver cache read 414 ms, Vulkan setup 0.58 s, 439 stored pipelines in 9 ms |
| Luxerion play, 9 min after the precompile | - | **0 synchronous compiles**; 10 new pipelines: 7 linked on the spot from precompiled libraries (0.7 ms in total), 3 needed a new library (3 frames held) |


## 4. Known gaps
(updated as the work lands)
- No harvest mode calling the game's area loaders: the library precompile covers every pixel shader without knowing pairings or state, which made it unnecessary on Turnip. On the Qualcomm driver (no pipeline libraries) misses still compile in the background (never blocking) and the stored list + shared caches are the only precompile.
- No progress screen: the first-boot precompile (~5 minutes, once per driver build) runs in the background with progress in the log; draws that need a library not built yet are skipped until it is (held frames), never compiled on the GPU thread.
- Turnip serializes pipeline library creation (4 threads = 1 thread), so the precompile can't go faster by adding threads.
- The driver cache with every pixel shader library is ~334 MB (read in 0.4 s at startup; saved at most every 3 minutes).
- Vertex shaders: only the unpatched container versions are known offline; the per-vertex-declaration variants the game uploads appear at runtime. Fix direction: translate vertex fetches with format/offset/stride taken from per-draw data instead of the instruction bits, keyed by a hash of the microcode with the vfetch fields masked, so one translation (and one pipeline library) serves every variant.
