package pro.sketchware.core.importmodel;

/**
 * Editor-side contract used by the import engine.
 *
 * <p>The core import model never touches Android UI classes. Anything that needs to know about an
 * actual editor (Sora, a plain text field, a test double, ...) is expressed through this gateway:
 * the editor owns the source text, the language of that source and the way a new source is applied
 * back.</p>
 */
public interface EditorImportGateway {

    /** @return the current source text of the edited document, never {@code null}. */
    String getSource();

    /** @return the language the current document is written in. */
    ImportLanguage getLanguage();

    /**
     * Applies a source produced by {@link ImportManager}. Implementations must only touch the
     * document when {@code result.changed} is {@code true} and should preserve the caret/selection
     * where reasonably possible.
     */
    void applySource(ImportModel.Result result);
}
