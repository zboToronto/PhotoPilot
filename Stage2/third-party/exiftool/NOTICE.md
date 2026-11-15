# ExifTool (bundled third-party software)

PhotoPilot's shutter count checker (Gear Inventory > Shutter Count Check) reads camera metadata with ExifTool by
Phil Harvey. The official Windows 64-bit package is bundled unmodified so that users do not need to install ExifTool
or Perl.

| Item | Value |
| --- | --- |
| Software | ExifTool 13.62, Windows 64-bit package `exiftool-13.62_64.zip` |
| Author | Phil Harvey — <https://exiftool.org/> |
| Downloaded | 2026-11-09, from the official download link on <https://exiftool.org/> (SourceForge project mirror `https://downloads.sourceforge.net/project/exiftool/exiftool-13.62_64.zip`) |
| Integrity | SHA-256 `9c79e97a9444ba1f893da1dcbcae378fba0c0a0112afd8bf426b396c75f884ce`, SHA-1 `289339ca2b0c83ad5438839cc0a2c101bcdfe33d`, MD5 `323a971caf71d231daefb3432ec9fd92` — all three match <https://exiftool.org/checksums.txt> |
| Contents | `exiftool-13.62_64/` is the extracted zip, byte for byte (511 files). `MANIFEST.sha256` lists every file with its SHA-256 and size. |

## Components and licences

- **ExifTool** — Copyright (c) 2003-2026, Phil Harvey. "This is free software; you can redistribute it and/or modify it
  under the same terms as Perl itself" (Perl's terms: the GNU General Public License or the Artistic License). See the
  header of `exiftool-13.62_64/exiftool_files/lib/Image/ExifTool.pm`.
- **Strawberry Perl** (runtime: `perl.exe`, `perl532.dll`, the core modules under `exiftool_files/lib`, and the MinGW
  runtime DLLs) — licences in `exiftool-13.62_64/exiftool_files/Licenses_Strawberry_Perl.zip`; see
  <https://strawberryperl.com/>.
- **Windows package and launcher** `exiftool(-k).exe` by Oliver Betz — CC0 1.0; see
  `exiftool-13.62_64/exiftool_files/readme_windows.txt`.
- `exiftool-13.62_64/exiftool_files/LICENSE` is the GNU General Public License version 3 text shipped in the package.

No file of the package was changed, renamed or removed. PhotoPilot starts `exiftool_files/perl.exe exiftool_files/exiftool.pl`
directly (what the launcher does), so the launcher keeps its original name. Its `(-k)` suffix would make ExifTool wait for a
key press, which a background process must not do.

## How PhotoPilot uses it

- Read-only metadata extraction of a photo the user selects (`-json`, never a write option), run locally only. No photo
  or metadata leaves the computer.
- ExifTool is started on the first request (never at application start), kept open with the official `-stay_open True -@ -`
  protocol, and stopped with `-stay_open False` when the profile context closes (user switch or application exit).
- Maven copies this folder into the build output and the JAR (`exiftool/` on the class path). Running from a JAR, the
  files are extracted once to a per-user cache, `%LOCALAPPDATA%\PhotoPilot\exiftool\<version>-<manifest hash>`, and verified
  against `MANIFEST.sha256` before use.

## Updating

1. Download the new `exiftool-<version>_64.zip` from the link on <https://exiftool.org/> and verify it against
   <https://exiftool.org/checksums.txt>.
2. Replace `exiftool-13.62_64/` with the extracted package, update `PACKAGE` in `ExifToolRuntime` and the `pom.xml` resource
   folder, and run `java tools/ExifToolManifest.java exiftool-<version>_64` from `Stage2`.
3. Re-check the Sony/Nikon shutter-count rules in `ShutterCountRules` against the new `lib/Image/ExifTool/Sony.pm` and
   `Nikon.pm`, and run the tests.
