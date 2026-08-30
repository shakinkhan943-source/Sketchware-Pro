package pro.sketchware.util.library;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pro.sketchware.SketchApplication;

/**
 * Resolves feature roots from the selected Compose JSON without hard-coded artifact filenames.
 *
 * <p>A feature lists the artifacts the user asked for — its <em>roots</em>. What the project needs
 * to compile and run is the roots <em>plus everything they depend on</em>, because Compose splits one
 * library across many artifacts: {@code androidx.compose.foundation:foundation} does not contain
 * {@code androidx.compose.foundation.layout}, {@code androidx.compose.ui:ui} does not contain
 * {@code Color} ({@code ui-graphics}), {@code Dp} ({@code ui-unit}) or {@code TextStyle}
 * ({@code ui-text}). Selecting the roots alone produced the very confusing report of imports that
 * "exist" in the bundle yet resolve to nothing.</p>
 */
final class ComposeDependencyFeatureResolver {
    private static final String TAG = "ComposeDependencyResolver";

    private ComposeDependencyFeatureResolver() {}

    static List<ComposeBuiltInLibraries.ComposeArtifact> select(
            ComposeBuiltInLibraries.ComposeManifest manifest,
            List<String> optionalFeatureIds) {
        if (manifest == null || manifest.artifacts == null || manifest.artifacts.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> ids = readFeatureArtifactIds(optionalFeatureIds);
        if (ids.isEmpty()) ids = readFeatureArtifactIds(manifest, optionalFeatureIds);
        if (ids.isEmpty()) {
            // Without usable feature roots the whole bundle is the selection, which is also the
            // layout of a flat (feature-less) manifest. Every artifact of such a package has to be
            // usable on its own, because nothing declares which one depends on which.
            List<ComposeBuiltInLibraries.ComposeArtifact> all = new ArrayList<>();
            for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
                if (artifact != null && !TextUtils.isEmpty(artifact.id)) all.add(artifact);
            }
            Log.d(TAG, "Compose package declares no features, using all " + all.size() + " artifacts");
            return all;
        }

        return expandDependencies(manifest, ids);
    }

    /** Fallback to the parsed manifest, e.g. when only the cached index of a package survives. */
    private static Set<String> readFeatureArtifactIds(
            ComposeBuiltInLibraries.ComposeManifest manifest, List<String> optionalFeatureIds) {
        Set<String> selected = new LinkedHashSet<>();
        if (manifest.features == null) return selected;
        Set<String> optional = optionalFeatureIds == null
                ? Collections.emptySet()
                : new LinkedHashSet<>(optionalFeatureIds);
        for (ComposeBuiltInLibraries.ComposeFeature feature : manifest.features) {
            if (feature == null) continue;
            if (!feature.required && !optional.contains(feature.id)) continue;
            if (feature.artifacts == null) continue;
            for (String artifactId : feature.artifacts) {
                if (!TextUtils.isEmpty(artifactId)) selected.add(artifactId);
            }
        }
        return selected;
    }

    /**
     * Walks {@link ComposeBuiltInLibraries.ComposeArtifact#dependencies} transitively from the roots.
     *
     * <p>Results keep the manifest order so the compile classpath, the resource merge and the dex
     * list stay stable between builds. The visited set makes a dependency cycle harmless instead of
     * an infinite loop.</p>
     */
    private static List<ComposeBuiltInLibraries.ComposeArtifact> expandDependencies(
            ComposeBuiltInLibraries.ComposeManifest manifest, Set<String> roots) {
        Map<String, ComposeBuiltInLibraries.ComposeArtifact> byName = indexByNames(manifest);

        Set<String> selected = new LinkedHashSet<>();
        Map<String, String> requiredBy = new HashMap<>();
        Deque<String> pending = new ArrayDeque<>(roots);
        for (String root : roots) requiredBy.put(root, "the selected Compose features");

        while (!pending.isEmpty()) {
            String name = pending.poll();
            ComposeBuiltInLibraries.ComposeArtifact artifact = find(byName, name);
            if (artifact == null) continue;
            requiredBy.remove(name);
            if (!selected.add(artifact.id)) continue;
            for (String dependency : artifact.dependencies) {
                if (TextUtils.isEmpty(dependency)) continue;
                pending.add(dependency);
                requiredBy.putIfAbsent(dependency, artifact.id);
            }
        }

        reportUnresolved(requiredBy);

        List<ComposeBuiltInLibraries.ComposeArtifact> result = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && selected.contains(artifact.id) && emitted.add(artifact.id)) {
                result.add(artifact);
            }
        }
        Log.d(TAG, "Selected " + result.size() + " Compose artifacts: " + describe(result));
        return result;
    }

    /**
     * A dependency the bundle does not declare is a broken build, not a warning, when the name is a
     * Compose artifact: those classes exist in no other place of a Sketchware build — not in
     * {@code android.jar}, not in the built-in libraries. Anything else (lifecycle, collection,
     * coroutines, kotlin-stdlib) is legitimately provided from somewhere else and is only logged.
     */
    private static void reportUnresolved(Map<String, String> requiredBy) {
        if (requiredBy.isEmpty()) return;

        List<String> missing = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        for (Map.Entry<String, String> entry : requiredBy.entrySet()) {
            if (isComposeArtifact(entry.getKey())) {
                missing.add(entry.getKey() + " (required by " + entry.getValue() + ")");
            } else {
                ignored.add(entry.getKey());
            }
        }
        if (!ignored.isEmpty()) {
            Log.d(TAG, "Ignoring dependencies the bundle does not declare, they are provided by the"
                    + " app or android.jar: " + ignored);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The Jetpack Compose dependency bundle is incomplete: "
                    + TextUtils.join("; ", missing) + " " + (missing.size() > 1 ? "are" : "is")
                    + " not declared in the Compose JSON, and no other part of a Sketchware build"
                    + " provides androidx.compose classes. kotlinc would only report the resulting"
                    + " gaps as unrelated 'Unresolved reference' or 'Cannot access class' errors."
                    + " Add every required artifact to the JSON and pack its classes.jar plus its dex"
                    + " file into the ZIP, then re-select both in Settings.");
        }
    }

    private static boolean isComposeArtifact(String name) {
        return name != null && name.toLowerCase().contains("compose");
    }

    /**
     * Indexes artifacts by every name they can be referenced with: their id, their Maven coordinate,
     * and a loose form of both where every non-alphanumeric character collapses to a single '_'.
     * A hand-written bundle tends to use {@code androidx.compose.ui:ui-unit} in one place and
     * {@code androidx_compose_ui_ui_unit} as a directory or dex file name in another.
     */
    private static Map<String, ComposeBuiltInLibraries.ComposeArtifact> indexByNames(
            ComposeBuiltInLibraries.ComposeManifest manifest) {
        Map<String, ComposeBuiltInLibraries.ComposeArtifact> index = new LinkedHashMap<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact == null) continue;
            putName(index, artifact.id, artifact);
            putName(index, artifact.coordinate, artifact);
            putName(index, looseName(artifact.id), artifact);
            putName(index, looseName(artifact.coordinate), artifact);
            // Coordinate without its version, e.g. "androidx.compose.ui:ui" for
            // "androidx.compose.ui:ui:1.6.0".
            String coordinate = artifact.coordinate;
            if (coordinate != null) {
                int lastColon = coordinate.lastIndexOf(':');
                if (lastColon > 0 && looksLikeVersion(coordinate.substring(lastColon + 1))) {
                    putName(index, coordinate.substring(0, lastColon), artifact);
                    putName(index, looseName(coordinate.substring(0, lastColon)), artifact);
                }
            }
        }
        return index;
    }

    private static void putName(Map<String, ComposeBuiltInLibraries.ComposeArtifact> index,
                                String name, ComposeBuiltInLibraries.ComposeArtifact artifact) {
        if (!TextUtils.isEmpty(name)) index.putIfAbsent(name, artifact);
    }

    private static ComposeBuiltInLibraries.ComposeArtifact find(
            Map<String, ComposeBuiltInLibraries.ComposeArtifact> index, String name) {
        if (TextUtils.isEmpty(name)) return null;
        ComposeBuiltInLibraries.ComposeArtifact artifact = index.get(name);
        if (artifact != null) return artifact;
        artifact = index.get(looseName(name));
        if (artifact != null) return artifact;

        int lastColon = name.lastIndexOf(':');
        if (lastColon > 0 && looksLikeVersion(name.substring(lastColon + 1))) {
            String withoutVersion = name.substring(0, lastColon);
            artifact = index.get(withoutVersion);
            if (artifact != null) return artifact;
            return index.get(looseName(withoutVersion));
        }
        return null;
    }

    private static String looseName(String name) {
        if (name == null) return null;
        StringBuilder result = new StringBuilder(name.length());
        boolean separator = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (separator && result.length() > 0) result.append('_');
                separator = false;
                result.append(Character.toLowerCase(c));
            } else {
                separator = result.length() > 0;
            }
        }
        return result.toString();
    }

    private static boolean looksLikeVersion(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c) && c != '.' && c != '-' && c != '_' && c != '+') return false;
        }
        return Character.isDigit(value.charAt(0));
    }

    private static String describe(List<ComposeBuiltInLibraries.ComposeArtifact> artifacts) {
        List<String> ids = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : artifacts) {
            ids.add(artifact.id);
        }
        return TextUtils.join(", ", ids);
    }

    private static Set<String> readFeatureArtifactIds(List<String> optionalFeatureIds) {
        Set<String> selected = new LinkedHashSet<>();
        File json = new File(SketchApplication.getAppContext().getCacheDir(),
                "compose-dependencies/source/compose-package.json");
        if (!json.isFile()) return selected;

        try (InputStream input = new FileInputStream(json)) {
            byte[] bytes = new byte[(int) Math.min(json.length(), Integer.MAX_VALUE)];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            JsonElement root = JsonParser.parseString(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return selected;
            JsonElement featureElement = root.getAsJsonObject().get("features");
            if (featureElement == null || !featureElement.isJsonArray()) return selected;

            Set<String> optional = optionalFeatureIds == null
                    ? Collections.emptySet()
                    : new LinkedHashSet<>(optionalFeatureIds);
            for (JsonElement element : featureElement.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject feature = element.getAsJsonObject();
                boolean required = feature.has("required") && feature.get("required").getAsBoolean();
                String id = feature.has("id") ? feature.get("id").getAsString() : "";
                if (!required && !optional.contains(id)) continue;

                JsonElement roots = feature.get("roots");
                if (roots == null) roots = feature.get("artifacts");
                if (roots instanceof JsonArray) {
                    for (JsonElement item : roots.getAsJsonArray()) {
                        if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                            String artifactId = item.getAsString();
                            if (!artifactId.isEmpty()) selected.add(artifactId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve Compose feature roots from the selected JSON: " + e.getMessage(), e);
        }
        return selected;
    }
}
