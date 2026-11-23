# libbox.aar — built, not committed

`libbox.aar` is the sing-box core for Android (the `io.nekohasekai.libbox`
bindings). It is **not published on Maven Central** — there are no
coordinates for it — so it is built from the sing-box source and placed
here before the Gradle build runs.

## How it is produced

- **Locally:** run `scripts/build-libbox.sh` from the repository root.
  It clones sing-box **v1.14.4**, runs `make lib_install` (gomobile) and
  `make lib_android`, and copies the resulting `libbox.aar` here.
  Requires Go 1.25.5, JDK 17, and Android NDK 28.0.13004108.
- **In CI:** the GitHub Actions workflow (`.github/workflows/build.yml`)
  does the same steps and copies the AAR here before `./gradlew` runs.

## What is in this directory (in git)

- `.gitkeep` — keeps the directory in git.
- `README.md` — this file.
- `libbox.aar` — **not in git** (see `.gitignore`); a build artifact.

## Verification

The Java API used by the app was verified against the bindings shipped
in the official sing-box-for-android 1.14.4 APK (dexdump):
`Libbox.setup` / `checkConfig` / `version`, `CommandServer`
(`start`, `startOrReloadService`, `closeService`, `close`),
`CommandServerHandler`, `PlatformInterface` (26 methods, incl.
`openTun`), `TunOptions`, `SetupOptions`, `OverrideOptions`.
