package io.mesosphere.types.gen.repository.internal;

/**
 * An interface that provides additional metadata for every file in the repository
 */
public interface RepositoryFile {

    /**
     * Return the file name. You can use this name to open the file
     * @return
     */
    String getName();

    /**
     * Return the MIME type of the file
     * @return
     */
    String getMimeType();

}
