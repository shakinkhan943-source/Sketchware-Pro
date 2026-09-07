package pro.sketchware.core.importmodel.integration;

import java.io.File;

import pro.sketchware.core.importmodel.EditorImportGateway;
import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.ImportModel;
import pro.sketchware.util.FileUtil;

/**
 * {@link EditorImportGateway} over a Java/Kotlin file on disk.
 *
 * <p>Used where no editor is open — e.g. the Design Activity acting on the currently selected
 * activity's user source file. The file is only rewritten when the engine changed something.</p>
 */
public final class FileImportGateway implements EditorImportGateway {

    private final File file;
    private final ImportLanguage language;

    public FileImportGateway(File file) {
        this.file = file;
        this.language = ImportService.languageOf(file.getName());
    }

    public File file() {
        return file;
    }

    @Override
    public String getSource() {
        return FileUtil.readFile(file.getAbsolutePath());
    }

    @Override
    public ImportLanguage getLanguage() {
        return language;
    }

    @Override
    public void applySource(ImportModel.Result result) {
        if (result == null || !result.changed) return;
        FileUtil.writeFile(file.getAbsolutePath(), result.source);
    }
}
