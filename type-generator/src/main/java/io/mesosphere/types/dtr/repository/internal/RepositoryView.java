package io.mesosphere.types.dtr.repository.internal;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;

/**
 * A view to the filesystem of a repository
 */
public interface RepositoryView {

    /**
     * List all files files in the current view
     */
    List<RepositoryFile> listFiles();

    /**
     * Open the specified file
     */
    InputStream openFile(String filename) throws FileNotFoundException;

    /**
     * Check if the specified file exists
     */
    Boolean exists(String filename);

}
