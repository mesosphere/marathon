package io.mesosphere.types.dtr.models.internal.scopes;

import io.mesosphere.types.dtr.models.internal.TypeScope;

/**
 * A scope for an in-line definition within a type
 */
public class TypeInlineScope extends TypeScope {

    /**
     * The last anonymous ID used
     */
    private static Integer lastId = 0;

    /**
     * Constructor of an anonymous inline scope
     * @param parent The parent scope to extend
     * @param contextName The name of the context for which we create an inline type.
     */
    public TypeInlineScope(TypeScope parent, String contextName) {
        super(parent, "inline_" + contextName + "_" + (++lastId));
    }

}
