package io.mesosphere.types.dtr.models.internal.scopes;

import io.mesosphere.types.dtr.models.internal.TypeScope;

/**
 * A File scope is used when a parser is reading a file
 */
public class FileScope extends TypeScope {

    /**
     * The filename of this scope
     */
    public String filename;

    /**
     * Instantiate a new
     *
     * @param parent   The reference to the parent scope
     * @param filename The full path to the file
     */
    public FileScope(TypeScope parent, String filename) {
        super(parent, "file:" + filename);
        this.filename = filename;
    }

}
