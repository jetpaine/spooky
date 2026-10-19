# Spooky: ARCore occlusion prototype

An Android test app that puts a smoky, vaguely humanoid shadow into the camera view and walks it
around. It tests how well **ARCore Depth API occlusion** hides the figure when it walks behind
real objects like cars, trees, posts and people.

## About the Depth API

The Depth API isn't part of the base Android SDK. It ships in the **ARCore SDK**
(`com.google.ar:core`, backed by *Google Play Services for AR*) and works on
[ARCore devices that support depth](https://developers.google.com/ar/devices), which covers most
mid-range and flagship phones. It doesn't need a ToF/LiDAR sensor: depth is mainly estimated
from camera motion, and a ToF sensor improves it if the phone has one. Call
`session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)` to check a device at runtime. The app
does this and shows the result on screen.

## Build and run

1. Open the project in Android Studio (Ladybug or newer) and let Gradle sync. It uses AGP 8.7,
   Kotlin 2.0, compileSdk 35 and minSdk 24.
2. Plug in an ARCore-supported phone with USB debugging on and press Run.
   (`./gradlew installDebug` also works from the command line once the Android SDK is installed.)
3. On first launch, allow camera access. If Google Play Services for AR is missing, the app sends
   you to the Play Store.

## Using it

- **Tap the ground** to summon the shadow. It stands there and turns to face you.
- **Tap more points** to give it a walking loop. It walks waypoint 1 → 2 → … → back to 1, up to
  8 points; adding a 9th drops the oldest. Small cyan dots mark the waypoints.
- **Occlusion: ON/OFF** turns depth-based occlusion on and off, so you can compare.
- **Depth view** tints the camera feed with the live depth map (red = near, blue = ~8 m,
  grey = no data). This shows what the occlusion is working from.
- **Clear** removes the figure and all waypoints.

The status bar shows tracking state, depth-map resolution, distance to the figure and FPS.

### Suggested first test (car or tree)

1. Stand 3–5 m from a parked car. Move the phone side to side for a few seconds so tracking and
   depth start up. The status line changes from `Depth: waiting` to e.g. `Depth: 160x90`.
2. Tap a point on the ground in front of the car, then a point **behind** it, where only the
   ground past the car is visible. If you can't see ground behind it, tap beside the car, then
   walk around and tap behind it.
3. Watch it walk back and forth. With occlusion ON, the body should disappear behind the car's
   silhouette. With it OFF, it draws on top. Turn on Depth view to see where the depth map's
   edges really are.

## How it works

| File | What it does |
| --- | --- |
| `MainActivity.kt` | ARCore session setup (`DepthMode.AUTOMATIC`), GL render loop, tap → hit test → anchors, status UI |
| `BackgroundRenderer.kt` | Draws the camera feed (external OES texture) plus the optional depth tint |
| `DepthTexture.kt` | Uploads `frame.acquireDepthImage16Bits()` (mm, uint16) into a `GL_RG8` texture; NDC → depth UV transform |
| `ShadowFigure.kt` | CPU-side figure: steering between waypoints, procedural walk cycle on a 17-joint skeleton, ~420 body particles, ~200 rising wisps, glowing eyes |
| `ShadowFigureRenderer.kt` | Draws particles as soft, noise-modulated point sprites and does **per-pixel occlusion** against the depth map |

Occlusion is done per fragment. Each particle's view-space depth is compared with the real-world
depth at the same screen pixel, with a soft band of ±1.5% of distance and a 5-tap blur to hide
the depth map's blocky edges. This is the same model as Google's `hello_ar` occlusion sample.
Pixels with no depth data, or closer than 15 cm or farther than ~8 m, count as visible.

Tuning knobs:
- `kTolerancePerMm` and the blur radius (`1.5 / textureSize`) in `ShadowFigureRenderer.kt`
  control how hard or soft occlusion edges are.
- `WALK_SPEED`, `STRIDE` and particle counts and sizes are in `ShadowFigure.kt`.
- Figure height is the `ShadowFigure(heightMeters)` constructor argument.

## What to expect from depth-based occlusion

- **Resolution is low.** The depth map is usually around 160×90 to 320×180 and is smoothed, so
  occlusion edges are soft and can bleed a few cm past a real edge. Cars, walls, trunks and
  people work well; thin branches, chain-link fences and leaves won't.
- **Best range is about 0.5–5 m.** Quality drops past that, and ARCore stops at ~8 m.
- **The phone has to move.** Depth from motion needs parallax, so a perfectly still phone gives
  stale or poor depth.
- **Moving objects lag.** A car driving by will "ghost" for a moment.
- **Texture-less or shiny surfaces**, such as glossy car paint, glass and blank walls, can
  produce holes. Holes count as visible, so the figure may show through them.

## Possible next steps

- Compare against **raw depth** (`acquireRawDepthImage16Bits` + confidence image): sharper
  but sparse.
- Edge-aware upsampling of the depth map using the camera image, to sharpen occlusion edges.
- Use the Depth API for collision as well as occlusion, so the shadow walks around obstacles.
