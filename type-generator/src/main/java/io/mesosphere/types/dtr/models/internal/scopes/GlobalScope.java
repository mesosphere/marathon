package io.mesosphere.types.dtr.models.internal.scopes;

import io.mesosphere.types.dtr.models.internal.TypeScope;

/**
 * A File scope is used when a parser is reading a file
 */
public class GlobalScope extends TypeScope{

    /**
     * Instantiate a new global scope
     */
    public GlobalScope() {
    super(null, "global");
    }

}
