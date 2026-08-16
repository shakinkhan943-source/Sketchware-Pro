# Built-in Jetpack Compose bundle

The Compose bundle is generated from resolved Maven dependency graphs by the GitHub Actions workflow
(`.github/workflows/build-compose-bundle.yml`), which runs `build_compose_bundle.py` to dynamically
resolve the essential Compose artifacts via Gradle, dex each one separately with `d8`, and package them
into `app/src/main/assets/libs/compose-libs.zip` + `compose-libraries.json`.

`apply_builtin_compose.py` is a one-time migration script that wired Compose into the core build
pipeline (`BuildConfig.java`, `ProjectFilePaths.java`, `ProjectBuilder.java`, `ResourceCompiler.java`).
That integration is now committed directly in those files, so the script no longer needs to run as
part of CI — it's kept here for reference/history only. Do not run it against a tree that already has
the integration applied; it is not idempotent and will duplicate the inserted blocks.
