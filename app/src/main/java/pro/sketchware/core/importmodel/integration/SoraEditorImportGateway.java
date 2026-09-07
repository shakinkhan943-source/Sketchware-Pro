package pro.sketchware.core.importmodel.integration;

import io.github.rosemoe.sora.widget.CodeEditor;

import pro.sketchware.core.importmodel.EditorImportGateway;
import pro.sketchware.core.importmodel.ImportLanguage;
import pro.sketchware.core.importmodel.ImportModel;

/**
 * {@link EditorImportGateway} backed by a Sora {@link CodeEditor}.
 *
 * <p>Sora stays exactly as it is: this gateway only reads the document, reports the language of
 * the edited file and writes a new text back when — and only when — the engine actually changed
 * something. The caret line/column is restored after the replacement so a resolve does not throw
 * the user back to the top of the file.</p>
 */
public final class SoraEditorImportGateway implements EditorImportGateway {

    private final CodeEditor editor;
    private final ImportLanguage language;

    public SoraEditorImportGateway(CodeEditor editor, ImportLanguage language) {
        this.editor = editor;
        this.language = language;
    }

    @Override
    public String getSource() {
        return editor.getText().toString();
    }

    @Override
    public ImportLanguage getLanguage() {
        return language;
    }

    @Override
    public void applySource(ImportModel.Result result) {
        if (result == null || !result.changed) {
            return;
        }

        int line = 0;
        int column = 0;
        try {
            line = editor.getCursor().getLeftLine();
            column = editor.getCursor().getLeftColumn();
        } catch (Throwable ignored) {
        }

        // Imports only ever grow or shrink the header, so keeping the caret on the same line
        // shifted by the header delta puts it back on the user's code.
        int lineDelta = countLines(result.source) - countLines(getSource());

        editor.setText(result.source);

        try {
            int targetLine = Math.max(0, Math.min(line + lineDelta, editor.getText().getLineCount() - 1));
            int lineLength = editor.getText().getColumnCount(targetLine);
            editor.setSelection(targetLine, Math.max(0, Math.min(column, lineLength)));
        } catch (Throwable ignored) {
        }
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }
}
