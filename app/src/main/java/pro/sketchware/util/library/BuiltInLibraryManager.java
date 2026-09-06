package pro.sketchware.util.library;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import pro.sketchware.core.project.BuiltInLibrary;
import pro.sketchware.core.build.ProjectBuilder;
import pro.sketchware.util.library.BuiltInLibraries;

/**
 * A class to keep track of a project's built-in libraries.
 */

public class BuiltInLibraryManager {

    private final ArrayList<String> libraryNames = new ArrayList<>();
    private final ArrayList<BuiltInLibrary> libraries = new ArrayList<>();
    private final List<BuiltInLibraries.BuiltInLibrary> excludedLibraries;

    public BuiltInLibraryManager(String projectId) {
        excludedLibraries = ExcludeBuiltInLibrariesConfig.getExcludedLibraries(projectId);
    }

    /**
     * Add a built-in library to the project libraries list.
     * Won't add a library if it's in the list already,
     * or it got excluded with {@link pro.sketchware.activities.editor.manage.library.ExcludeBuiltInLibrariesActivity}.
     *
     * @param libraryName The built-in library's name, e.g. material-1.0.0
     */
    public void addLibrary(String libraryName) {
        Optional<BuiltInLibraries.BuiltInLibrary> library = BuiltInLibraries.BuiltInLibrary.ofName(libraryName);
        //noinspection SimplifyOptionalCallChains because #isEmpty() isn't available on Android.
        if (!library.isPresent() || !excludedLibraries.contains(library.get())) {
            if (!libraryNames.contains(libraryName)) {
                Log.d(ProjectBuilder.TAG, "Added built-in library \"" + libraryName + "\" to project's dependencies");
                libraryNames.add(libraryName);
                libraries.add(new BuiltInLibrary(libraryName));
                addDependencies(libraryName);
            }
        } else {
            addDependencies(libraryName);
        }
    }

    private void addDependencies(String libraryName) {
        for (String libraryDependency : BuiltInLibraryUtils.getKnownDependencies(libraryName)) {
            addLibrary(libraryDependency);
        }
    }

    public boolean containsLibrary(String libraryName) {
        Optional<BuiltInLibraries.BuiltInLibrary> library = BuiltInLibraries.BuiltInLibrary.ofName(libraryName);
        //noinspection SimplifyOptionalCallChains because #isEmpty() isn't available on Android.
        if (!library.isPresent()) {
            return false;
        }
        return libraries.contains(new BuiltInLibrary(library.get().getName()));
    }

    /**
     * Removes a previously added built-in library again, without touching the libraries it pulled in.
     *
     * <p>Used when the project gets a newer copy of the same library from the Jetpack store: two
     * versions of one library in a single APK is not a warning, because the DEX merge keeps the first
     * definition of every type, so whichever copy lost would still be referenced by code compiled
     * against the other.</p>
     *
     * @param libraryName the built-in library's name, e.g. kotlinx-coroutines-android-1.11.0
     */
    public void removeLibrary(String libraryName) {
        if (libraryNames.remove(libraryName)) {
            libraries.remove(new BuiltInLibrary(libraryName));
            Log.d(ProjectBuilder.TAG, "Removed built-in library \"" + libraryName
                    + "\" from project's dependencies");
        }
    }

    /**
     * @return {@link BuiltInLibraryManager#libraries}
     */
    public ArrayList<BuiltInLibrary> getLibraries() {
        return libraries;
    }
}
