# S7 Health Scanner

An Android app for scanning storage health on Samsung Galaxy S7 Edge (and other Android devices). It performs file-level read verification and bit-pattern write/read tests on accessible storage, and supports optional root-based raw block scanning for devices with `su` access.

## Features

- **Accessible storage scan (no root required)**
  - Enumerates readable files in accessible storage areas.
  - Reads file contents in chunks and reports read I/O errors.
  - Performs a **bit-by-bit pattern verification test**: writes known patterns (`0x00`, `0xFF`, `0xAA55`, `0x55AA`, pseudo-random), fsyncs, reads back, and compares every byte.
  - Reports health status: Healthy / Degraded / Critical.

- **Raw block scan (root required)**
  - Detects root (`su`).
  - Enumerates block devices from `/dev/block/by-name` and `/proc/partitions`.
  - Performs read-only sector/block reads via `su dd` and reports unreadable regions/offsets.
  - Does **not** write to raw block devices.

- **Repair / Mark unusable**
  - File-level: quarantines damaged/unreadable files by moving them to app-private storage (`filesDir/quarantine`) so other apps cannot access them.
  - Raw block: physical bad-sector remapping is handled by flash storage controller/firmware and cannot be reliably repaired by an Android app. The app exports a report with bad region offsets for further diagnosis (e.g., `fsck`/`badblocks` on unmounted partitions with root).

- **Report export**
  - Exports a text report with device info, scan stats, and bad regions.

- **Crash/reboot resume**
  - Scan progress is checkpointed before each file/chunk. If the device restarts (for example when hitting a bad sector), the app offers to resume on next launch and skips the suspected problem chunk to avoid a reboot loop.

## Important limitations

Android's security model prevents normal apps from performing true physical sector-by-sector scans of internal flash memory:

- **Without root:** apps cannot read raw block devices (`/dev/block/*`). This app scans accessible files and performs bit-pattern verification on files it creates in accessible storage. This verifies storage behavior at the filesystem/file level, not physical controller sectors.
- **With root:** the app can perform read-only block reads using `su` + `dd`. This is closer to a sector/block scan, but it still operates through the OS/block layer; flash controllers may remap bad sectors transparently.
- **Repair:** physical NAND/eMMC/UFS bad sectors cannot be truly "repaired" by userspace software. The appropriate response is usually controller remapping, filesystem repair, or storage replacement. This app quarantines damaged accessible files and reports raw bad regions; it does not write to raw block devices.

## Build

Requirements:
- JDK 17
- Android SDK (API 34, build tools)
- Gradle 8.2+ / Android Gradle Plugin 8.2.2

```bash
gradle :app:assembleDebug
```

The APK will be at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## CI / Release

GitHub Actions workflow: `.github/workflows/build-release.yml`.

The workflow builds the debug APK and uploads it as an artifact. Releases are published in the GitHub Releases section with the built APK attached.

## Project structure

```
app/
  src/main/
    AndroidManifest.xml
    java/com/buboadonis/s7health/
      model/ScanModels.kt
      scanner/StorageScanner.kt
      scanner/RawBlockScanner.kt
      ui/MainActivity.kt
    res/
      layout/activity_main.xml
      values/
```

## License

MIT (see repository license if added).
