package pro.sketchware.util.library;

import pro.sketchware.beans.ProjectLibraryBean;

import pro.sketchware.core.project.ProjectDataManager;

/**
 * Manages the Jetpack Compose library state for a project. Unlike Material3,
 * Compose does not depend on AppCompat, so its enabled state maps directly to
 * the {@link ProjectLibraryBean#isEnabled()} flag of the Compose library bean.
 */
public class ComposeLibraryManager {

    private final ProjectLibraryBean composeLibraryBean;

    public ComposeLibraryManager(String sc_id) {
        composeLibraryBean = ProjectDataManager.getLibraryManager(sc_id).getCompose();
    }

    public ComposeLibraryManager(ProjectLibraryBean projectLibraryBean) {
        composeLibraryBean = projectLibraryBean;
    }

    public boolean isComposeEnabled() {
        return composeLibraryBean != null && composeLibraryBean.isEnabled();
    }

    public ProjectLibraryBean getComposeLibraryBean() {
        return composeLibraryBean;
    }
}
