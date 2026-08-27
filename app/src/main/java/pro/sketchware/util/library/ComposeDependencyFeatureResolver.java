package pro.sketchware.util.library;

import android.text.TextUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import pro.sketchware.SketchApplication;

/**
 * Resolves feature -> artifact ids from the selected JSON. The current package
 * schema calls these entries "roots", while newer packages may call them
 * "artifacts". No artifact filenames are embedded here.
 */
final class ComposeDependencyFeatureResolver {
    private ComposeDependencyFeatureResolver() {}

    static List<ComposeBuiltInLibraries.ComposeArtifact> select(
            ComposeBuiltInLibraries.ComposeManifest manifest,
            List<String> optionalFeatureIds) {
        if (manifest == null || manifest.artifacts == null || manifest.artifacts.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> ids = readFeatureArtifactIds(optionalFeatureIds);
        if (ids.isEmpty()) {
            for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
                if (artifact != null && !TextUtils.isEmpty(artifact.id)) ids.add(artifact.id);
            }
        }

        List<ComposeBuiltInLibraries.ComposeArtifact> result = new ArrayList<>();
        for (ComposeBuiltInLibraries.ComposeArtifact artifact : manifest.artifacts) {
            if (artifact != null && ids.contains(artifact.id)) result.add(artifact);
        }
        return result;
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
            JsonElement root = JsonParser.parseString(new String(bytes, 0, offset, java.nio.charset.StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return selected;
            JsonElement featureElement = root.getAsJsonObject().get("features");
            if (!featureElement.isJsonArray()) return selected;

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
