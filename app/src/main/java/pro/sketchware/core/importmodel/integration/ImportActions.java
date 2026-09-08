package pro.sketchware.core.importmodel.integration;

import android.app.Activity;

import java.util.List;

import pro.sketchware.R;
import pro.sketchware.core.async.BackgroundTasks;
import pro.sketchware.core.async.TaskHost;
import pro.sketchware.core.importmodel.EditorImportGateway;
import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.ImportModel;
import pro.sketchware.util.Helper;
import pro.sketchware.util.LogUtil;
import pro.sketchware.util.SketchwareUtil;

/**
 * Glue between a UI surface (Design Activity, source editor) and the import engine.
 *
 * <p>The heavy part — building/refreshing the symbol index from the project classpath and running
 * the resolver — happens on a background thread; only {@link EditorImportGateway#applySource} and
 * the summary toast run on the UI thread. Both actions are strictly manual.</p>
 */
public final class ImportActions {

    private static final String TAG = "ImportActions";

    private ImportActions() {
    }

    public static void resolveImports(Activity activity, String scId, EditorImportGateway gateway) {
        run(activity, scId, gateway, true);
    }

    public static void organizeImports(Activity activity, String scId, EditorImportGateway gateway) {
        run(activity, scId, gateway, false);
    }

    private static void run(Activity activity, String scId, EditorImportGateway gateway, boolean resolve) {
        if (activity == null || scId == null || gateway == null) return;

        final String source = gateway.getSource();
        final ImportLanguage language = gateway.getLanguage();

        final pro.sketchware.dialogs.ProgressDialog progress =
                new pro.sketchware.dialogs.ProgressDialog(activity);
        progress.setMessage(resolve ? "Resolving imports…" : "Organizing imports…");
        progress.show();

        BackgroundTasks.callIoIfAlive(TaskHost.of(activity), TAG,
                () -> resolve
                        ? ImportService.resolveImports(scId, source, language)
                        : ImportService.organizeImports(scId, source, language),
                result -> {
                    progress.dismiss();
                    if (result == null) return;
                    if (result.changed) {
                        gateway.applySource(result);
                    }
                    SketchwareUtil.toast(summary(result, resolve));
                },
                error -> {
                    progress.dismiss();
                    LogUtil.e(TAG, "Import processing failed", error);
                    SketchwareUtil.toastError(Helper.getResString(R.string.common_error_unknown));
                });
    }

    private static String summary(ImportModel.Result result, boolean resolve) {
        if (!result.changed && result.ambiguous.isEmpty() && result.unresolved.isEmpty()) {
            return resolve
                    ? Helper.getResString(R.string.import_model_nothing_to_resolve)
                    : Helper.getResString(R.string.import_model_imports_already_organized);
        }

        StringBuilder message = new StringBuilder();
        if (!result.added.isEmpty()) {
            message.append("+").append(result.added.size()).append(" ");
        }
        if (!result.removed.isEmpty()) {
            message.append("-").append(result.removed.size()).append(" ");
        }
        message.append(Helper.getResString(R.string.import_model_imports_word));
        appendNames(message, Helper.getResString(R.string.import_model_ambiguous_word), result.ambiguous);
        appendNames(message, Helper.getResString(R.string.import_model_unresolved_word), result.unresolved);
        return message.toString().trim();
    }

    private static void appendNames(StringBuilder message, String label, List<String> names) {
        if (names.isEmpty()) return;
        message.append("\n").append(label).append(": ");
        for (int i = 0; i < names.size() && i < 5; i++) {
            if (i > 0) message.append(", ");
            message.append(names.get(i));
        }
        if (names.size() > 5) message.append("…");
    }
}
