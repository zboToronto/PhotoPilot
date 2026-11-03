# Application icons

Every icon asset derives from the supplied logo, `EECS3311/Glossy AI Camera Flower Logo.png` (1254 × 1254 RGBA, outside this repository). The logo was not redesigned, cropped or stretched; the source file is only read.

| Asset | Path (relative to Stage2) | Used by |
| --- | --- | --- |
| PNG 16, 32, 48, 64, 128, 256, 512 | `src/main/resources/com/photopilot/ui/gui/icons/photopilot-<size>.png` | Runtime classpath: window/taskbar icons and the in-app logo |
| Windows ICO (16, 24, 32, 48, 64, 128, 256) | `packaging/windows/photopilot.ico` | Future Windows packaging (for example `jpackage --icon`) |
| macOS ICNS (16–1024, including @2x) | `packaging/macos/photopilot.icns` | Future macOS packaging |

ICO entries below 256 px are 32-bit BMP with an AND mask for older readers; the 256 px entry is PNG. Nothing here is packaged yet.

Regenerate from Stage2 with JDK 21 (no extra dependencies; Lanczos-3 resampling in premultiplied alpha):

```powershell
java tools/GenerateAppIcons.java "../../Glossy AI Camera Flower Logo.png"
```

## Runtime use

`AppIcons.windowIcon` sets the full PNG set on every PhotoPilot stage and dialog: the main window, the borderless workspace chooser, `AppDialogs`, the startup recovery dialog and the startup error alert. The OS picks the closest size. `AppIcons.logo(size)` draws the logo in the chooser title bar, the chooser heading (fitted to its existing 66 px design row) and the workspace header. Resources load through `Class.getResource`, so they work from Maven, IDEs and a future JAR.

## Limits

- The native file chooser (avatar picker) is an OS dialog; it shows the OS's own icon.
- macOS ignores `Stage.getIcons()` for the Dock; the Dock icon comes from the packaged `.icns` (or shows the Java icon during `javafx:run`). Linux desktop entries similarly need packaging metadata.
- While running unpackaged, Windows groups the taskbar button under `java.exe`; a pinned shortcut keeps the Java icon until PhotoPilot is packaged with the ICO.
- Verified on Windows 11 at 2560 × 1440; other scales, macOS and Linux are unverified.
