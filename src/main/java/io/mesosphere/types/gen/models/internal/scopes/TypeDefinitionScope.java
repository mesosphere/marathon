package io.mesosphere.types.gen.models.internal.scopes;

import io.mesosphere.types.gen.models.internal.TypeScope;

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
