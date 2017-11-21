package io.mesosphere.types.dtr.models.internal;

import io.mesosphere.types.dtr.models.internal.types.AnnotativeType;
import io.mesosphere.types.dtr.models.internal.types.StructuralType;

import java.util.concurrent.CompletableFuture;

/**
 * Base class to all type definitions
 */
public abstract class Type {

    /**
     * The parent type
     */
    private Type parent;

    /**
     * An identifier used to uniquely identify this type for caching purposes
     */
    private String id;

    /**
     * Create a named type with parent
     * @param parent The parent type
     * @param id The name of the type
     */
    public Type(Type parent, String id) {
        this.parent = parent;
        this.id = id;
    }

    /**
     * Create a named type with parent
     * @param parentFuture The future to the parent type
     * @param id The name of the type
     */
    public Type(CompletableFuture<Type> parentFuture, String id) {
        this.id = id;
        parentFuture.whenComplete((type, throwable) -> {
            this.parent = type;
        });
    }

    /**
     * Walks up the parent hierectary until we find a structural parent type and returns that type
     * @return Return the structural parent type
     */
    public StructuralType getStructuralParent() {
        if (parent == null) return null;
        return parent.getStructural();
    }

    /**
     * Walks up the parent hierectary until we find an annotation parent type and returns that type
     * @return Return the annotative parent type
     */
    public AnnotativeType getAnnotativeParent() {
        if (parent == null) return null;
        return parent.getAnnotative();
    }

    /**
     * Return this or the first available parent structural type
     * @return Return the structural type that represents this type
     */
    public StructuralType getStructural() {
        if (this instanceof StructuralType) {
            return (StructuralType) this;
        } else {
            return getStructuralParent();
        }
    }

    /**
     * Return this or the first available parent structural type
     * @return Return the structural type that represents this type
     */
    public AnnotativeType getAnnotative() {
        if (this instanceof AnnotativeType) {
            return (AnnotativeType) this;
        } else {
            return getAnnotativeParent();
        }
    }

    /**
     * @return Return the parent type
     */
    public Type getParent() {
        return parent;
    }

    /**
     * @return Return the type ID
     */
    public String getId() {
        return id;
    }

    /**
     * Return the name of the type as string
     * @return The name of the type as string
     */
    public abstract String getTypeName();

    /**
     * Checks if this type equals to another type (by value)
     * @param type The type to compare against
     * @return Returns `true` if the types match
     */
    public abstract Boolean equals(Type type);

//    /**
//     * Return a clone of this type
//     * @return A type clone
//     */
//    public abstract Type clone();

}
