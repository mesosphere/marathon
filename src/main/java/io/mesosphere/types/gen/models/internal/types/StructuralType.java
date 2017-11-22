package io.mesosphere.types.gen.models.internal.types;

import io.mesosphere.types.gen.models.internal.Type;

import java.util.concurrent.CompletableFuture;

/**
 * Base class for all structural types
 */
public abstract class StructuralType extends Type {

    /**
     * Create a named type with parent
     * @param parent The parent type
     * @param id The name of the type
     */
    public StructuralType(Type parent, String id) {
        super(parent, id);
    }

    /**
     * Create a named type with parent
     * @param parentFuture The future to the parent type
     * @param id The name of the type
     */
    public StructuralType(CompletableFuture<Type> parentFuture, String id) {
        super(parentFuture, id);
    }

}
