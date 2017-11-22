package io.mesosphere.types.gen.models.internal.types;

import io.mesosphere.types.gen.models.internal.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A union type represents multiple types, differentiated by a particular
 * differentiation rule.
 */
public class UnionType extends StructuralType {

    /**
     * The different types in the union, differentiated by the value of the
     * discriminator property.
     */
    public List<Type> unionTypes;

    /**
     * A Union Type
     * @param unionTypes A list of the types that compose this union
     */
    public UnionType(Type parent, String id, List<Type> unionTypes) {
        super(parent, id);
        this.unionTypes = unionTypes;
    }

    /**
     * A Union Type
     * @param parentFuture A list of the types that compose this union
     */
    public UnionType(CompletableFuture<Type> parentFuture, String id, List<CompletableFuture<Type>> unionTypeFutures) {
        super(parentFuture, id);
        this.unionTypes = new ArrayList<>();
        unionTypeFutures.forEach(typeCompletableFuture -> {
            typeCompletableFuture.whenComplete((type, throwable) -> {
                this.unionTypes.add(type);
            });
        });
    }

    @Override
    public String toString() {
        return "UnionType{" +
                "id='" + getId() + '\'' +
                ", unionTypes=" + unionTypes +
                '}';
    }

    /**
     * Return the kind of this object as string
     * @return Returns `union`
     */
    @Override
    public String getTypeName() {
        return "union";
    }

    /**
     * Checks if the given type is union, with equal types
     * @param type The type to compare against
     * @return
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof UnionType)) return false;
        if (((UnionType) type).unionTypes.size() != unionTypes.size()) return false;
        return unionTypes.containsAll(((UnionType) type).unionTypes);
    }

}
