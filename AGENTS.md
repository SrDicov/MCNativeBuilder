# AGENTS.md

## Filosofía
- Unix + POSIX al pie de la letra: una cosa bien, componible, texto como interfaz, sin sorpresas.
- Ultra-optimizado: exprimir el 100% del hardware donde corre (perfilar antes de optimizar, `ponytail:` a todo atajo con techo conocido).

Single-module Gradle Java app. Entrypoint `src/main/java/me/judge/mcnativebuilder/Main.java` (`me.judge.mcnativebuilder.Main`).

## Build
- `./gradlew build` → `build/libs/MCNativeBuilder-1.0-SNAPSHOT-all.jar`. Gradle 9.4.1 wrapper, no system install needed.
- Fat jar requires `shadowJar` (finalized by `addExtraLibsToShadowJar`, embeds `libs/` under `extraLibs/`). Don't substitute `jar`.
- `libs/*.jar` are committed and resolved via `flatDir { dirs("libs") }` as `blank:` coords (`build.gradle`). Keep jar file + dependency line in sync.

## Run
- `java -jar build/libs/MCNativeBuilder-1.0-SNAPSHOT-all.jar --graalvm $JAVA_HOME --mc-version 1.21.11 --dry-run`
- `--graalvm` or `GRAALVM_HOME` required. Flags are store-true (`--dry-run`, no `=true`).
- `--mc-version` selects MC (`--version` kept as alias). `--workdir`/`MCNATIVE_WORKDIR` overrides cwd for `install/ assets/ libraries/`; `--username`/`MCNATIVE_USERNAME` labels interactive login (defaults: cwd, OS user).
- Auth reuses cached non-expired session; prompts only on a tty, else exits 2. Exit codes: 0 ok, 1 build/runtime failure, 2 CLI misuse. Child stdout/stderr pass through (inherited, not relogged).
- `--dry-run` skips MS login + tracing-agent game launch; full run needs interactive login, GraalVM `native-image`, and network (rewrites LWJGL to 3.3.3 from `build.lwjgl.org`).
- Cwd-sensitive: creates `install/<version>/` (native-image `@command.txt` + output live here), `assets/`, `libraries/` in workdir. All ignored by git.
- `--extra-build-args` is repeatable but REQUIRES `=` form (`--extra-build-args=-J-Xmx12g`); space form with `-` values is rejected by argparse4j. Each occurrence is one arg, spaces intact.
- Shipped reflection defaults (`src/main/resources/.../reflect-config.json`: `java.io.File`, authlib classes + records) are NOT seen by native-image from the tool jar (not on game `-cp`); `mergeDefaultReflectConfig()` unions them into `<buildDir>/reflect-config.json` at build time.
- Multiplayer needs `-H:+SharedArenaSupport` (Netty FFM close path) — in the fixed arg list.
- `--march compatibility` (default, portable) or `native` (max perf, binary only runs on this CPU). `--gc` likewise.
- `--gc` also switches native-image's own builder JVM. `epsilon` never collects, so the builder needs its full ~11 GB peak as free heap (died at 9-10 GB, passed at 12.5 GB), and at runtime it leaks until crash. Keep `serial`.
- The analysis needs >10.5 GB heap: on 15 GB boxes pass `--extra-build-args -J-Xmx12g`, close the browser, and drop caches (`echo 1 > /proc/sys/vm/drop_caches`). Verified: 9.3/10.3/10.5 GB died in `[1/8]`, 12.57 GB reached `[8/8]`.
- No PGO on GraalVM 25: `--pgo-instrument` no longer exists (verified against `native-image --help` + expert options), so `-O pgoi/pgo` fail fast with exit 2. Max verified combo: `-O2 --march native --gc serial`.

## Processors (`processors/IProcessor.java`)
- `LWJGLProcessor` always added; `FabricProcessor` only if `--fabric` (switches main class to `me.judge.fabric.FabricMain`); `AndroidProcessor` only if `--android`. Custom ones via `--processors <FQN>`.
- Order per build: `processClasspath` → `preBuild` → `runCompileBlocking`. `postBuild` is currently noop everywhere.

## Gotchas
- Gradle 9.4.1 needs JDK <= 25 (fails on 26+ with "Unsupported class file major version 71"). Build here: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build`.
- GraalVM CE 25.0.2 at `/opt/graalvm` for `--graalvm` (plain JDK has no `bin/native-image`). Minecraft native-image needs ~12 GB RAM and 20-40 min on 4 cores.
- No `src/test`; `test { useJUnitPlatform() }` only. Don't add test infra unprompted.
- Android build no-ops its native flags without `ANDROID_NDK` env (`AndroidProcessor.java:38`).
- CI (`.github/workflows/gradle.yml`) is `workflow_dispatch`-only, GraalVM 25 Liberica, Linux needs 15 GB swap + `zip`.
