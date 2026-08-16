#!/usr/bin/env python3
"""
CLI wrapper to build the Jetpack Compose built-in bundle and optionally install
it into the repository's assets directory.

This wraps scripts/compose/build_compose_bundle.py and provides convenient
arguments for CI or local use.
"""
from __future__ import annotations
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD_SCRIPT = ROOT / "scripts" / "compose" / "build_compose_bundle.py"
DEFAULT_OUT = ROOT / "app" / "src" / "main" / "assets" / "libs"


def run_build(env: dict | None = None) -> int:
    env = env or os.environ.copy()
    cmd = [sys.executable, str(BUILD_SCRIPT)]
    print("Running:", " ".join(map(str, cmd)))
    return subprocess.run(cmd, env=env, cwd=ROOT).returncode


def copy_outputs(out_dir: Path, dest_dir: Path, overwrite: bool = True) -> None:
    out_dir = out_dir.resolve()
    dest_dir = dest_dir.resolve()
    manifest = out_dir / "compose-libraries.json"
    archive = out_dir / "compose-libs.zip"

    if not manifest.exists() or not archive.exists():
        raise FileNotFoundError(f"Expected outputs not found in {out_dir}: {manifest.name}, {archive.name}")

    dest_dir.mkdir(parents=True, exist_ok=True)

    dest_manifest = dest_dir / manifest.name
    dest_archive = dest_dir / archive.name

    if dest_manifest.exists() and not overwrite:
        raise FileExistsError(f"Destination manifest exists: {dest_manifest}")
    if dest_archive.exists() and not overwrite:
        raise FileExistsError(f"Destination archive exists: {dest_archive}")

    shutil.copy2(manifest, dest_manifest)
    shutil.copy2(archive, dest_archive)
    print(f"Installed {archive.name} and {manifest.name} -> {dest_dir}")


def locate_android_jar(platform: str, sdk_root: str | None) -> Path | None:
    # If ANDROID_JAR env was provided, let build script handle it. This helper tries to
    # resolve a path if the user requested an explicit check.
    if not sdk_root:
        return None
    sdk = Path(sdk_root)
    candidate = sdk / "platforms" / platform / "android.jar"
    if candidate.exists():
        return candidate
    return None


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Build and install Jetpack Compose built-in bundle")
    p.add_argument("--compose-ui", help="Set COMPOSE_UI_VERSION", default=None)
    p.add_argument("--compose-material3", help="Set COMPOSE_MATERIAL3_VERSION", default=None)
    p.add_argument("--activity-compose", help="Set ACTIVITY_COMPOSE_VERSION", default=None)
    p.add_argument("--navigation-compose", help="Set NAVIGATION_COMPOSE_VERSION", default=None)
    p.add_argument("--lifecycle-compose", help="Set LIFECYCLE_COMPOSE_VERSION", default=None)
    p.add_argument("--android-platform", help="Set ANDROID_COMPILE_SDK (eg android-36)", default=None)
    p.add_argument("--android-sdk-root", help="ANDROID_SDK_ROOT (if needed)", default=os.environ.get("ANDROID_SDK_ROOT"))
    p.add_argument("--android-jar", help="Path to android.jar to pass as ANDROID_JAR", default=None)
    p.add_argument("--out-dir", help="Where build outputs land (the build script's OUT)", default=str(DEFAULT_OUT))
    p.add_argument("--install-to", help="If provided, copy the produced zip/json into this directory", default=None)
    p.add_argument("--overwrite", help="Overwrite existing destination files", action="store_true")
    p.add_argument("--no-run", help="Don't run build script, just install existing outputs", action="store_true")
    p.add_argument("--verbose", help="Show more logs", action="store_true")

    args = p.parse_args(argv)

    env = os.environ.copy()
    if args.compose_ui:
        env["COMPOSE_UI_VERSION"] = args.compose_ui
    if args.compose_material3:
        env["COMPOSE_MATERIAL3_VERSION"] = args.compose_material3
    if args.activity_compose:
        env["ACTIVITY_COMPOSE_VERSION"] = args.activity_compose
    if args.navigation_compose:
        env["NAVIGATION_COMPOSE_VERSION"] = args.navigation_compose
    if args.lifecycle_compose:
        env["LIFECYCLE_COMPOSE_VERSION"] = args.lifecycle_compose
    if args.android_platform:
        env["ANDROID_COMPILE_SDK"] = args.android_platform
    if args.android_sdk_root:
        env["ANDROID_SDK_ROOT"] = args.android_sdk_root
    if args.android_jar:
        env["ANDROID_JAR"] = args.android_jar

    out_dir = Path(args.out_dir)

    if not args.no_run:
        # Basic prechecks
        if not BUILD_SCRIPT.exists():
            print(f"Build script not found: {BUILD_SCRIPT}")
            return 2

        # If android.jar not set, try to locate it for helpful error message
        if not env.get("ANDROID_JAR") and env.get("ANDROID_SDK_ROOT"):
            possible = locate_android_jar(env.get("ANDROID_COMPILE_SDK", "android-36"), env.get("ANDROID_SDK_ROOT"))
            if possible:
                env["ANDROID_JAR"] = str(possible)
                if args.verbose:
                    print("Auto-located android.jar:", env["ANDROID_JAR"])

        rc = run_build(env=env)
        if rc != 0:
            print("build_compose_bundle.py failed with exit code", rc)
            return rc

    # Install/copy outputs if requested
    if args.install_to:
        dest_dir = Path(args.install_to)
        try:
            copy_outputs(out_dir, dest_dir, overwrite=args.overwrite)
        except Exception as e:
            print("Installation failed:", e)
            return 3

    print("Done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
