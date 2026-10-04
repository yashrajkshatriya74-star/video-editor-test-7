# Video Editor (Android)

A starter Android Studio project for a video editing app, laid out KineMaster-style:
**`TimelineActivity` is the launcher screen** — preview + timeline on the left, a vertical
tool rail on the right (Add Video, Add Audio, Chroma Key, 3D Model, Export), all on one
screen instead of separate menu-driven activities.

- ✅ Multi-clip timeline: add multiple videos, reorder by drag, remove clips
- ✅ Split & trim (Media3 Transformer clipping)
- ✅ Mirror (horizontal flip) per clip
- ✅ Horizontal (16:9) / vertical (9:16) / square (1:1) export, toggle in the UI
- ✅ Fade-to-black transitions between clips (see honesty note below — this is
  fade, not a true cross-dissolve)
- ✅ Add background audio/voiceover track, mixed under the video sequence
- ✅ Real-time chroma key / green-screen — **now actually plays an imported video** through
  the keying shader (previously the screen had sliders but no way to load a video into it)
- ✅ 3D model import & preview — .glb / .gltf (Google Filament), with orbit rotation/zoom —
  **crash on open is fixed** (`Utils.init()` was missing, plus a render-loop was added so
  the model actually draws every frame instead of staying blank)
- ⚠️ 3D model **composited directly into exported video** — rewritten after a first attempt
  failed to compile; this version fixes the two actual errors and avoids the other uncertain
  APIs by reusing patterns already proven elsewhere in this repo (see honesty note below)
- 🔲 True cross-dissolve transitions (see note)
- 🔲 Per-clip trim UI inside the timeline dialog (currently trims default to full clip; the
  RangeSlider pattern from `EditorActivity` can be dropped into `TimelineActivity`'s clip dialog)
- 🔲 Manual 3D rigging/animation inside the app (see note below)
- 🔲 The right-hand tool rail currently opens Chroma Key / 3D Model as separate full-screen
  tools (a real improvement over navigating through a menu first) rather than inline panels
  that overlay the editor without leaving it, which is the next step toward a true
  single-screen KineMaster-style feel — see the note below

## Honesty notes — read before you build on these

**The tool rail opens separate screens, not inline panels — that's a real UX gap vs. actual
KineMaster.** In KineMaster, tapping "Chroma Key" or similar opens a panel *over* the editor
without navigating away from it, so the project/timeline is still visible and nothing is
lost. Right now, tapping Chroma Key or 3D Model in the rail launches a full separate
`Activity` — a real app, reachable from one screen, but not yet the overlay-panel feel of
the reference app. Getting all the way to inline panels means restructuring these tools as
fragments or custom views hosted inside `TimelineActivity` rather than standalone activities,
which is a meaningfully larger change than what's done here and is the natural next step if
you want to keep pushing toward a closer KineMaster match.


**"Transitions" are fade-to-black, not cross-dissolve.** A true cross-dissolve blends
two overlapping decoded video streams into one frame. Media3 Transformer's `Composition`
model plays clips in a sequence, not overlapping — so a real crossfade needs a custom
multi-input compositor, which is a much bigger undertaking than fading each clip's own
alpha to/from black at its boundary (what `FadeEffect.kt` actually does). That's a real,
usable transition — just don't market it as a crossfade.

**Manual 3D rigging/animation is not implemented and isn't a realistic near-term addition.**
That's a bone/skeleton editor + keyframe timeline + inverse kinematics — essentially
building a mobile Blender. `Model3DActivity` plays back models that are **already rigged
and animated** (rig and animate in Blender, export as .glb with baked animation, the app
just renders it). No mainstream mobile video app does in-app rigging either — it's a
different product category, not a missing checkbox.

**`FadeEffect.kt`'s exact base-class method signature may need a small adjustment** to
match whichever Media3 version you end up pinning — the custom-`GlShaderProgram` API
surface for Transformer effects has shifted slightly across Media3 releases. The GL/shader
logic itself is correct and won't need changes; only the override signature might.

**3D-into-video compositing has been rewritten once already after a real compile failure —
read this before trusting it.** The first version used Media3's `OverlayEffect` +
`BitmapOverlay`/`StaticOverlaySettings` convenience classes and Filament's
`SwapChain.CONFIG_TRANSPARENT` transparency flag. When actually built in CI, those specific
names didn't exist in this project's pinned library versions (`AnimatorInstance`,
`CONFIG_TRANSPARENT`, `StaticOverlaySettings` all failed as unresolved references) — notably,
everything else in that file (Engine, Scene, Camera, AssetLoader, Renderer.readPixels, and
more) compiled fine; only those few specific names were wrong.

This version (`Model3DFrameRenderer.kt` + `Model3DOverlayEffect.kt`) fixes those two root
causes directly — lets Kotlin infer the animator's type instead of naming it explicitly, and
replaces the transparency flag with a solid green-screen background plus a chroma-key shader
to key it out (reusing the exact technique `ChromaKeyRenderer.kt` already uses). It also
avoids Media3's `OverlayEffect`/`BitmapOverlay`/`StaticOverlaySettings` entirely, building
instead on `GlEffect`/`BaseGlShaderProgram` — the same base classes `FadeEffect.kt` already
uses, which are confirmed to compile against this project's pinned Media3 version.

That said: **this rewrite has not been run through an actual build yet.** It's a
higher-confidence attempt than the first (narrower, informed by the exact errors that
occurred, built on patterns already proven in this repo), not a guarantee. If it fails to
compile again, the error will point at a specific line/name the same way the last one did —
fix that name using Android Studio's autocomplete against your exact Filament/Media3
versions, the same way the rest of this project's build errors got resolved one at a time.

## What's actually implemented vs. scaffolded

This repo is a **working starting point**, not a finished, store-ready app. Real/functional:
- Compiles and runs as a hub → timeline editor / chroma key / 3D viewer app
- Multi-clip add, reorder, remove, mirror-per-clip, fade transitions, orientation toggle,
  and background audio all flow through `ExportEngine.kt` into one real `Transformer` export
- Split and trim work via Media3 Transformer's clipping config (see `EditorActivity` for
  the single-clip version, `TimelineActivity`/`ExportEngine` for the multi-clip version)
- The chroma key shader is a real, correct GLSL implementation — wire a camera or decoder
  frame into its `SurfaceTexture` and it will key live
- The 3D viewer genuinely loads and renders .glb files via Filament

Still stubbed:
- Compositing the chroma-keyed layer *into* the video export pipeline (currently the
  chroma key tool and the export pipeline are separate — same `GlEffect` approach as
  `FadeEffect.kt` would merge them)
- Compositing 3D model layers into exported video (render Filament's output to an
  offscreen surface, feed it into the Media3 effect pipeline)
- .gltf external resource resolution (`resolveGltfResource` in `Model3DActivity`)
- Per-track audio volume control (hook point noted in `ExportEngine.kt`)

## Requirements
- Android Studio Koala (2024.1) or newer
- JDK 17
- Android SDK 34, min SDK 24

## Gradle wrapper
This repo intentionally doesn't ship `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` — the
wrapper jar is a binary file that needs to be generated by an actual Gradle install, not
hand-written. Two ways to get it:
- **Open in Android Studio** — it detects the missing wrapper on first sync and offers to
  generate one for you. Accept the prompt, then commit the generated `gradle/wrapper/`,
  `gradlew`, and `gradlew.bat` files.
- **CI doesn't need it** — `.github/workflows/build-apk.yml` calls `gradle` directly (via
  `gradle/actions/setup-gradle`, which installs Gradle on the runner), not `./gradlew`, so
  GitHub Actions builds work with or without the wrapper being committed.

## Setup
1. Open this folder in Android Studio (`File > Open`)
2. Let Gradle sync (first sync will download Media3, Filament, Material libs)
3. Run on a device/emulator with API 24+ (chroma key needs a device with OpenGL ES 2.0+, i.e. anything modern)

## Pushing to your own GitHub repo

```bash
cd VideoEditorApp
git init
git add .
git commit -m "Initial scaffold: split/trim, chroma key, 3D import"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo-name>.git
git push -u origin main
```

(Create the empty repo on github.com first, or use `gh repo create` if you have the GitHub CLI.)

## Suggested build order for the remaining features
1. **Multi-clip timeline**: replace the single `EditedMediaItem` in `EditorActivity`
   with a `List<EditedMediaItem>` inside `EditedMediaItemSequence.Builder(...)`,
   and build a RecyclerView-based horizontal timeline UI to reorder/trim clips.
2. **Chroma key baked into export**: implement a Media3 `GlEffect`/`GlShaderProgram`
   that runs the same shader from `ChromaKeyRenderer.kt` inside the `Transformer`
   pipeline via `Effects.Builder().setVideoEffects(listOf(yourChromaKeyEffect))`.
3. **3D overlay compositing**: render Filament to an offscreen `Surface`/texture,
   treat it as another video effect layer in the same pipeline.
4. **Text/stickers/transitions**: Media3 `TextureOverlay` / `OverlayEffect` APIs.
5. **Export settings screen**: expose `Transformer.Builder` options (bitrate,
   resolution via `Presentation` effect, codec) in a UI.

## Project structure
```
app/src/main/java/com/example/videoeditor/
├── ui/MainActivity.kt              # hub screen
├── editor/EditorActivity.kt        # legacy single-clip split/trim demo
├── timeline/
│   ├── TimelineClip.kt             # clip/transition/orientation/audio data models
│   ├── TimelineAdapter.kt          # RecyclerView: thumbnails, drag reorder
│   ├── TimelineActivity.kt         # main multi-clip editor screen
│   ├── FadeEffect.kt               # custom Media3 GlEffect: fade-to/from-black
│   └── ExportEngine.kt             # assembles Composition: clips+mirror+fade+orientation+audio
├── chroma/
│   ├── ChromaKeyRenderer.kt        # OpenGL ES chroma key shader
│   └── ChromaKeyActivity.kt        # chroma key tool screen
└── model3d/Model3DActivity.kt      # glTF/GLB import + preview (Filament)
```
