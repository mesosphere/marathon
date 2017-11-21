package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An option from a pre-defined set of values
 */
public class EnumType extends StructuralType {

    /**
     * The type of the array elements
     */
    public Type itemType;

    /**
     * The minimum number of items this array must always accommodate
     */
    public List<Object> values;

    /**
     * Simple constructor with enum values
     * @param parentType The parent type
     * @param itemType The type of the enum items
     * @param itemValues The item values
     */
    public EnumType(Type parentType, String id, Type itemType, List<Object> itemValues) {
        super(parentType, id);
        this.itemType = itemType;
        this.values = new ArrayList<>(itemValues);
    }

    /**
     * Simple constructor with enum values
     * @param parentTypeFuture The parent type future
     * @param itemTypeFuture The type of the enum items
     * @param itemValues The item values
     */
    public EnumType(CompletableFuture<Type> parentTypeFuture, String id, CompletableFuture<Type> itemTypeFuture, List<Object> itemValues) {
        super(parentTypeFuture, id);
        itemTypeFuture.whenComplete((type, throwable) -> {
            this.itemType = type;
        });
        this.values = new ArrayList<>(itemValues);
    }

    @Override
    public String toString() {
        return "EnumType{" +
                "id=" + getId() +
                ", type=" + itemType +
                ", values=" + values +
                '}';
    }

    /**
     * Return the kind of this object as string
     * @return Returns `enum`
     */
    @Override
    public String getTypeName() {
        return "enum";
    }

    /**
     * Checks if this enum type is equal to the given type by checking
     * if all values are the same.
     *
     * @param type The type to compare against
     * @return Returns true if the two types are equal
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof EnumType)) return false;
        if (((EnumType) type).values.size() != values.size()) return false;
        return values.containsAll(((EnumType) type).values);
    }

}
