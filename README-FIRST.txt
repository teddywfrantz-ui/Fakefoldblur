Fold Blur POC v0.5.0 — Dual-display AGSL version

What changed:
- One AGSL shader composites BOTH outer + inner screenshots directly.
- Hinge angle drives a spatial left-to-right transition front.
- Blur is strongest around the moving front and clears behind it.
- Added localized warp, edge shadow, and restrained highlight for a more optical transition.
- Uses Presentation API on any secondary/internal display Samsung exposes during the handoff.
- Presentation windows request KEEP_SCREEN_ON + TURN_SCREEN_ON.
- Foreground service holds a CPU wake lock so hinge processing stays alive while the screen handoff occurs.
- One UI Home remains your real launcher.

Important limitation:
Samsung still controls physical panel power and whether an inactive internal display is exposed to third-party Presentation windows. This app requests the behavior but cannot override OEM display policy without root/SystemUI access.

If your existing GitHub Actions workflow already works, KEEP IT. You only need to replace the app files from this project.
