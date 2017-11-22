package io.mesosphere.types.gen.repository.internal;

import io.mesosphere.types.gen.repository.impl.LocalRepository;

/**
 * A "Repository" is the abstraction layer for accessing the type data
 * stored in some sort of registry. This could either be a local folder,
 * a remote URL or a database.
 */
public abstract class Repository {

    /**
     * Instantiate a repository object based on the URI given
     * @param uri
     * @return
     */
    public static Repository fromURI(String uri) throws IllegalArgumentException {
        if (uri.startsWith("file:")) {
            return new LocalRepository(uri.substring(5));
        } else {
            throw new IllegalArgumentException("Unknown repository URI schema provided (expecting 'file:')");
        }
    }

    /**
     * Get high-level accessor to the specified project and type version
     * @param project
     */
    public abstract RepositoryView openProjectTypes(String project, String version);

    /**
     * Get high-level accessor to the specified project and compiler version
     */
    public abstract RepositoryView openProjectCompiler(String project, String version);

}
