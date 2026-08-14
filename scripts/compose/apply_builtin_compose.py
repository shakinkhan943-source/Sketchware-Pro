from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def patch(path, replacements):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    original = text
    for old, new in replacements:
        if old not in text:
            if new in text:
                continue
            raise RuntimeError(f"Patch anchor not found in {path}: {old[:100]!r}")
        text = text.replace(old, new, 1)
    if text != original:
        p.write_text(text, encoding="utf-8")


patch("app/src/main/java/pro/sketchware/core/project/BuildConfig.java", [
    (
        "    public boolean isHttp3Used = false;\n",
        "    public boolean isHttp3Used = false;\n\n"
        "    /** True when the project has Jetpack Compose enabled. */\n"
        "    public boolean isComposeEnabled = false;\n\n"
        "    /** Optional Compose feature IDs selected by the user. */\n"
        "    public ArrayList<String> composeOptionalFeatures = new ArrayList<>();\n",
    )
])

patch("app/src/main/java/pro/sketchware/core/build/ProjectFilePaths.java", [
    (
        "        ProjectLibraryBean googleMaps = projectLibraryManager.getGoogleMap();\n",
        "        ProjectLibraryBean googleMaps = projectLibraryManager.getGoogleMap();\n"
        "        ProjectLibraryBean compose = projectLibraryManager.getCompose();\n",
    ),
    (
        "        if (googleMaps.useYn.equals(ProjectLibraryBean.LIB_USE_Y)) {\n",
        "        if (compose != null && compose.useYn.equals(ProjectLibraryBean.LIB_USE_Y)) {\n"
        "            buildConfig.isComposeEnabled = true;\n"
        "            Object optionalFeatures = compose.configurations == null ? null\n"
        "                    : compose.configurations.get(\"compose_optional_features\");\n"
        "            if (optionalFeatures instanceof List<?>) {\n"
        "                for (Object featureId : (List<?>) optionalFeatures) {\n"
        "                    if (featureId instanceof String && !((String) featureId).isEmpty()) {\n"
        "                        buildConfig.composeOptionalFeatures.add((String) featureId);\n"
        "                    }\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "        if (googleMaps.useYn.equals(ProjectLibraryBean.LIB_USE_Y)) {\n",
    )
])

patch("app/src/main/java/pro/sketchware/core/build/ProjectBuilder.java", [
    (
        "import pro.sketchware.util.library.BuiltInLibraries;\n",
        "import pro.sketchware.util.library.BuiltInLibraries;\n"
        "import pro.sketchware.util.library.ComposeBuiltInLibraries;\n",
    ),
    (
        "        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {\n"
        "            classpath.append(\":\").append(BuiltInLibraries.getLibraryClassesJarPathString(library.getName()));\n"
        "        }\n\n        /* Add local libraries to the classpath */",
        "        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {\n"
        "            classpath.append(\":\").append(BuiltInLibraries.getLibraryClassesJarPathString(library.getName()));\n"
        "        }\n\n"
        "        /* Add the separate built-in Jetpack Compose bundle. */\n"
        "        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "            classpath.append(\":\").append(ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id).getAbsolutePath());\n"
        "        }\n\n        /* Add local libraries to the classpath */",
    ),
    (
        "        return extraPackages + localLibraryManager.getPackageNameLocalLibrary();\n"
        "    }\n\n    /**\n     * Compiles the project's Java sources",
        "        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "            if (artifact.packageName != null && !artifact.packageName.isEmpty()) {\n"
        "                extraPackages.append(artifact.packageName).append(\":\");\n"
        "            }\n"
        "        }\n"
        "        return extraPackages + localLibraryManager.getPackageNameLocalLibrary();\n"
        "    }\n\n"
        "    /** Returns the selected artifact closure from the separate Compose bundle. */\n"
        "    public List<ComposeBuiltInLibraries.ComposeArtifact> getSelectedComposeArtifacts() {\n"
        "        if (!projectFilePaths.buildConfig.isComposeEnabled) {\n"
        "            return java.util.Collections.emptyList();\n"
        "        }\n"
        "        return ComposeBuiltInLibraries.getSelectedArtifacts(projectFilePaths.buildConfig.composeOptionalFeatures);\n"
        "    }\n\n    /**\n     * Compiles the project's Java sources",
    ),
    (
        "            for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {\n"
        "                apkBuilder.addResourcesFromJar(BuiltInLibraries.getLibraryClassesJarPath(library.getName()));\n"
        "            }\n\n            for (String jarPath : localLibraryManager.getJarLocalLibrary().split(\":\")) {",
        "            for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {\n"
        "                apkBuilder.addResourcesFromJar(BuiltInLibraries.getLibraryClassesJarPath(library.getName()));\n"
        "            }\n\n"
        "            for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "                apkBuilder.addResourcesFromJar(ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id));\n"
        "            }\n\n            for (String jarPath : localLibraryManager.getJarLocalLibrary().split(\":\")) {",
    ),
    (
        "        for (BuiltInLibrary builtInLibrary : builtInLibraryManager.getLibraries()) {\n"
        "            dexes.add(BuiltInLibraries.getLibraryDexFile(builtInLibrary.getName()));\n"
        "        }\n\n        /* Add local libraries' main DEX files */",
        "        for (BuiltInLibrary builtInLibrary : builtInLibraryManager.getLibraries()) {\n"
        "            dexes.add(BuiltInLibraries.getLibraryDexFile(builtInLibrary.getName()));\n"
        "        }\n\n"
        "        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "            dexes.add(ComposeBuiltInLibraries.getLibraryDexFile(artifact.id));\n"
        "        }\n\n        /* Add local libraries' main DEX files */",
    ),
    (
        "    public void buildBuiltInLibraryInformation() {\n        if (projectFilePaths.buildConfig.isAppCompatEnabled) {",
        "    public void buildBuiltInLibraryInformation() {\n"
        "        if (projectFilePaths.buildConfig.isComposeEnabled) {\n"
        "            ComposeBuiltInLibraries.ensureExtracted();\n"
        "        }\n\n        if (projectFilePaths.buildConfig.isAppCompatEnabled) {",
    ),
    (
        "        }\n        KotlinCompilerBridge.maybeAddKotlinBuiltInLibraryDependenciesIfPossible(this, builtInLibraryManager);\n",
        "        }\n        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "            // Validation is intentionally lightweight; the bundle contains all transitive artifacts.\n"
        "            if (!ComposeBuiltInLibraries.getLibraryClassesJarPath(artifact.id).exists()) {\n"
        "                throw new IllegalStateException(\"Missing built-in Compose artifact: \" + artifact.id);\n"
        "            }\n"
        "        }\n\n"
        "        KotlinCompilerBridge.maybeAddKotlinBuiltInLibraryDependenciesIfPossible(this, builtInLibraryManager);\n",
    ),
    (
        "        }\n    }\n\n    /**\n     * Generates default ProGuard R.java rules",
        "        }\n        for (ComposeBuiltInLibraries.ComposeArtifact artifact : getSelectedComposeArtifacts()) {\n"
        "            File config = ComposeBuiltInLibraries.getLibraryProguardConfiguration(artifact.id);\n"
        "            if (config.exists()) {\n"
        "                args.add(\"-include\");\n"
        "                args.add(config.getAbsolutePath());\n"
        "            }\n"
        "        }\n    }\n\n    /**\n     * Generates default ProGuard R.java rules",
    )
])

patch("app/src/main/java/pro/sketchware/core/build/compiler/ResourceCompiler.java", [
    (
        "import pro.sketchware.util.library.BuiltInLibraries;\n",
        "import pro.sketchware.util.library.BuiltInLibraries;\n"
        "import pro.sketchware.util.library.ComposeBuiltInLibraries;\n",
    ),
    (
        "        private final File compiledLocalLibraryResourcesDirectory;\n",
        "        private final File compiledLocalLibraryResourcesDirectory;\n"
        "        private final File compiledComposeLibraryResourcesDirectory;\n",
    ),
    (
        "            compiledLocalLibraryResourcesDirectory = new File(SketchApplication.getAppContext().getCacheDir(), \"compiledLocalLibs\");\n",
        "            compiledLocalLibraryResourcesDirectory = new File(SketchApplication.getAppContext().getCacheDir(), \"compiledLocalLibs\");\n"
        "            compiledComposeLibraryResourcesDirectory = new File(SketchApplication.getAppContext().getCacheDir(), \"compiledComposeLibs\");\n",
    ),
    (
        "            /* Add local libraries' assets */\n",
        "            /* Add selected Compose feature assets */\n"
        "            for (ComposeBuiltInLibraries.ComposeArtifact artifact : buildHelper.getSelectedComposeArtifacts()) {\n"
        "                File assets = ComposeBuiltInLibraries.getLibraryAssetsPath(artifact.id);\n"
        "                if (assets.exists()) {\n"
        "                    args.add(\"-A\");\n"
        "                    args.add(assets.getAbsolutePath());\n"
        "                }\n"
        "            }\n\n            /* Add local libraries' assets */\n",
    ),
    (
        "            /* Include compiled local libraries' resources */\n",
        "            /* Include compiled Compose resources */\n"
        "            for (ComposeBuiltInLibraries.ComposeArtifact artifact : buildHelper.getSelectedComposeArtifacts()) {\n"
        "                File cachedZip = new File(compiledComposeLibraryResourcesDirectory, artifact.id + \".zip\");\n"
        "                if (cachedZip.exists()) {\n"
        "                    args.add(\"-R\");\n"
        "                    args.add(cachedZip.getAbsolutePath());\n"
        "                }\n"
        "            }\n\n            /* Include compiled local libraries' resources */\n",
    ),
    (
        "            compileBuiltInLibraryResources();\n            LogUtil.d(TAG + \":c\", \"Compiling built-in library resources took \" + (System.currentTimeMillis() - savedTimeMillis) + \" ms\");\n",
        "            compileBuiltInLibraryResources();\n"
        "            compileComposeLibraryResources();\n"
        "            LogUtil.d(TAG + \":c\", \"Compiling built-in library resources took \" + (System.currentTimeMillis() - savedTimeMillis) + \" ms\");\n",
    ),
    (
        "        private void compileLocalLibraryResources() throws SimpleException, MissingFileException {",
        "        private void compileComposeLibraryResources() throws SimpleException, MissingFileException {\n"
        "            compiledComposeLibraryResourcesDirectory.mkdirs();\n"
        "            for (ComposeBuiltInLibraries.ComposeArtifact artifact : buildHelper.getSelectedComposeArtifacts()) {\n"
        "                File resourceDirectory = ComposeBuiltInLibraries.getLibraryResourcesPath(artifact.id);\n"
        "                if (!resourceDirectory.exists()) continue;\n"
        "                File cachedZip = new File(compiledComposeLibraryResourcesDirectory, artifact.id + \".zip\");\n"
        "                ArrayList<String> commands = new ArrayList<>();\n"
        "                commands.add(aapt2.getAbsolutePath());\n"
        "                commands.add(\"compile\");\n"
        "                commands.add(\"--dir\");\n"
        "                commands.add(resourceDirectory.getAbsolutePath());\n"
        "                commands.add(\"-o\");\n"
        "                commands.add(cachedZip.getAbsolutePath());\n"
        "                BinaryExecutor executor = new BinaryExecutor();\n"
        "                executor.setCommands(commands);\n"
        "                if (!executor.execute().isEmpty()) throw new SimpleException(executor.getLog());\n"
        "            }\n"
        "        }\n\n        private void compileLocalLibraryResources() throws SimpleException, MissingFileException {",
    ),
])

print("Compose built-in integration patches applied")
