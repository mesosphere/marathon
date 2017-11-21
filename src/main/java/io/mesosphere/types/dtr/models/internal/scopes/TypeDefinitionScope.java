package io.mesosphere.types.dtr.models.internal.scopes;

import io.mesosphere.types.dtr.models.internal.TypeScope;

/**
 * A scope of the definition of a type
 */
public class TypeDefinitionScope extends TypeScope {

    /**
     * Constructor of a type scope
     * @param parent The parent scope
     * @param name The name of the type scope
     */
    public TypeDefinitionScope(TypeScope parent, String name) {
        super(parent, name);
    }

}
