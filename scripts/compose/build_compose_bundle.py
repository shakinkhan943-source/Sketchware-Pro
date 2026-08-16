#!/usr/bin/env python3
import json
import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/libs"
WORK = ROOT / "build/compose-bundle"
COMPOSE_UI = os.environ.get("COMPOSE_UI_VERSION", "1.7.8")
COMPOSE_MATERIAL3 = os.environ.get("COMPOSE_MATERIAL3_VERSION", "1.3.1")
ACTIVITY_COMPOSE = os.environ.get("ACTIVITY_COMPOSE_VERSION", "1.9.3")
NAVIGATION_COMPOSE = os.environ.get("NAVIGATION_COMPOSE_VERSION", "2.8.5")
LIFECYCLE_COMPOSE = os.environ.get("LIFECYCLE_COMPOSE_VERSION", "2.8.7")
ANDROID_PLATFORM = os.environ.get("ANDROID_COMPILE_SDK", "android-36")

FEATURES = {
    "core": {
        "name": "Compose Core",
        "description": "Required Compose runtime, UI and foundation APIs.",
        "required": True,
        "tag": "IMPORTANT",
        "roots": [
            f"androidx.compose.runtime:runtime:{COMPOSE_UI}",
            f"androidx.compose.ui:ui:{COMPOSE_UI}",
            f"androidx.compose.foundation:foundation:{COMPOSE_UI}",
        ],
    },
    "material3": {
        "name": "Material 3",
        "description": "Material 3 components and theming for Compose.",
        "required": True,
        "tag": "IMPORTANT",
        "roots": [f"androidx.compose.material3:material3:{COMPOSE_MATERIAL3}"],
    },
    "activity-compose": {
        "name": "Activity Compose",
        "description": "Integrates Compose content with Android activities.",
        "required": True,
        "tag": "IMPORTANT",
        "roots": [f"androidx.activity:activity-compose:{ACTIVITY_COMPOSE}"],
    },
    "animation": {
        "name": "Compose Animation",
        "description": "Animation APIs beyond the core foundation set.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.compose.animation:animation:{COMPOSE_UI}"],
    },
    "material": {
        "name": "Compose Material",
        "description": "Legacy Compose Material components.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.compose.material:material:{COMPOSE_UI}"],
    },
    "icons-extended": {
        "name": "Material Icons Extended",
        "description": "Extended Material icon set.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.compose.material:material-icons-extended:{COMPOSE_UI}"],
    },
    "ui-tooling-preview": {
        "name": "UI Tooling Preview",
        "description": "Preview annotations and tooling APIs.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.compose.ui:ui-tooling-preview:{COMPOSE_UI}"],
    },
    "navigation-compose": {
        "name": "Navigation Compose",
        "description": "Navigation APIs for Compose destinations.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.navigation:navigation-compose:{NAVIGATION_COMPOSE}"],
    },
    "lifecycle-viewmodel-compose": {
        "name": "Lifecycle ViewModel Compose",
        "description": "Lifecycle ViewModel integration for Compose.",
        "required": False,
        "tag": "OPTIONAL",
        "roots": [f"androidx.lifecycle:lifecycle-viewmodel-compose:{LIFECYCLE_COMPOSE}"],
    },
}


def run(*args, cwd=ROOT):
    print("+", " ".join(map(str, args)))
    subprocess.run(list(map(str, args)), cwd=cwd, check=True)


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    temp = WORK / "resolver.gradle"
    output = WORK / "resolved.json"

    configurations = []
    dependency_lines = []
    for feature_id, feature in FEATURES.items():
        config_name = "compose_" + feature_id.replace("-", "_")
        configurations.append((feature_id, config_name))
        dependency_lines.append(f"configurations.maybeCreate('{config_name}')")
        for root in feature["roots"]:
            dependency_lines.append(f"dependencies.add('{config_name}', '{root}')")

    groovy = f"""
// No repositories{{}} block here: this repo's settings.gradle sets
// dependencyResolutionManagement.repositoriesMode = FAIL_ON_PROJECT_REPOS,
// which means any project-level repositories{{}} declaration (including in
// this ad hoc build file) hard-fails the build. The root project already
// inherits google()/mavenCentral()/jitpack/sonatype from settings.gradle.
{chr(10).join(dependency_lines)}

tasks.register('dumpComposeArtifacts') {{
    doLast {{
        def result = [:]
        {chr(10).join([f"result['{fid}'] = configurations.getByName('{cname}').resolvedConfiguration.resolvedArtifacts.collect {{ a -> [file: a.file.absolutePath, module: a.moduleVersion.id.group + ':' + a.name + ':' + a.moduleVersion.id.version] }}" for fid, cname in configurations])}
        file('{output.as_posix()}').text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result))
    }}
}}
"""
    temp.write_text(groovy, encoding="utf-8")
    run("./gradlew", "-q", "-b", temp, "dumpComposeArtifacts")

    resolved = json.loads(output.read_text(encoding="utf-8"))
    required_files = set()
    feature_files = {}
    file_meta = {}

    required_features = [fid for fid, f in FEATURES.items() if f["required"]]
    for fid in required_features:
        feature_files[fid] = {Path(x["file"]).resolve() for x in resolved[fid]}
        required_files.update(feature_files[fid])

    for fid, feature in FEATURES.items():
        files = {Path(x["file"]).resolve() for x in resolved[fid]}
        if feature["required"]:
            selected = files
        else:
            selected = files - required_files
        feature_files[fid] = selected

    all_files = set(required_files)
    for files in feature_files.values():
        all_files.update(files)

    for fid, entries in resolved.items():
        for entry in entries:
            file = Path(entry["file"]).resolve()
            if file in all_files:
                file_meta[file] = entry["module"]

    bundle_root = WORK / "bundle"
    if bundle_root.exists():
        shutil.rmtree(bundle_root)
    (bundle_root / "libraries").mkdir(parents=True)
    (bundle_root / "dex").mkdir(parents=True)

    android_jar = Path(os.environ.get("ANDROID_JAR", ""))
    if not android_jar.exists():
        sdk = Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", "")))
        android_jar = sdk / "platforms" / ANDROID_PLATFORM / "android.jar"
    if not android_jar.exists():
        raise RuntimeError(f"android.jar not found: {android_jar}")

    def artifact_id(module):
        group, name, version = module.split(":", 2)
        safe_group = group.replace(".", "_")
        return f"{safe_group}__{name}__{version}"

    artifact_by_file = {}
    for file in sorted(all_files):
        module = file_meta[file]
        aid = artifact_id(module)
        artifact_by_file[file] = aid
        target = bundle_root / "libraries" / aid
        target.mkdir(parents=True, exist_ok=True)
        classes_jar = target / "classes.jar"
        res_dir = target / "res"
        assets_dir = target / "assets"
        proguard = target / "proguard.txt"

        if file.suffix.lower() == ".aar":
            with zipfile.ZipFile(file) as zf:
                if "classes.jar" in zf.namelist():
                    classes_jar.write_bytes(zf.read("classes.jar"))
                else:
                    classes_jar.write_bytes(b"")
                if "res/" in zf.namelist() or any(n.startswith("res/") for n in zf.namelist()):
                    res_dir.mkdir(exist_ok=True)
                    for name in zf.namelist():
                        if name.startswith("res/") and not name.endswith("/"):
                            out = target / name
                            out.parent.mkdir(parents=True, exist_ok=True)
                            out.write_bytes(zf.read(name))
                if any(n.startswith("assets/") for n in zf.namelist()):
                    for name in zf.namelist():
                        if name.startswith("assets/") and not name.endswith("/"):
                            out = target / name
                            out.parent.mkdir(parents=True, exist_ok=True)
                            out.write_bytes(zf.read(name))
                for rule_name in ("consumer-rules.pro", "proguard.txt"):
                    if rule_name in zf.namelist():
                        proguard.write_bytes(zf.read(rule_name))
                        break
        else:
            shutil.copy2(file, classes_jar)

        if not classes_jar.exists() or classes_jar.stat().st_size == 0:
            (bundle_root / "dex" / f"{aid}.dex").touch()
        else:
            d8 = shutil.which("d8")
            if not d8:
                candidates = sorted(Path(os.environ["ANDROID_SDK_ROOT"]).glob("build-tools/*/d8"))
                if not candidates:
                    raise RuntimeError("d8 executable not found")
                d8 = str(candidates[-1])
            dex_tmp = WORK / "dex-tmp"
            if dex_tmp.exists():
                shutil.rmtree(dex_tmp)
            dex_tmp.mkdir()
            run(d8, "--min-api", "23", "--lib", android_jar, "--output", dex_tmp, classes_jar)
            generated = dex_tmp / "classes.dex"
            shutil.move(generated, bundle_root / "dex" / f"{aid}.dex")

    artifacts = []
    for file, aid in sorted(artifact_by_file.items(), key=lambda item: item[1]):
        module = file_meta[file]
        group, name, version = module.split(":", 2)
        artifacts.append({
            "id": aid,
            "coordinate": module,
            "packageName": group,
            "dependencies": [],
        })

    for fid, feature in FEATURES.items():
        feature["artifacts"] = [artifact_by_file[f] for f in sorted(feature_files[fid])]

    manifest = {
        "schemaVersion": 1,
        "composeVersion": COMPOSE_UI,
        "features": [
            {k: v for k, v in feature.items() if k != "roots"}
            for feature in FEATURES.values()
        ],
        "artifacts": artifacts,
    }
    (bundle_root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (OUT / "compose-libraries.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    archive = OUT / "compose-libs.zip"
    if archive.exists():
        archive.unlink()
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
        for file in bundle_root.rglob("*"):
            if file.is_file():
                zf.write(file, file.relative_to(bundle_root).as_posix())

    print(f"Generated {archive} ({archive.stat().st_size} bytes) with {len(artifacts)} artifacts")


if __name__ == "__main__":
    main()
