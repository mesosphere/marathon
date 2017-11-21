package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;

import java.util.concurrent.CompletableFuture;

/**
 * An array type contains one or more items of the specified type
 */
public class ArrayType extends StructuralType {

    /**
     * The type of the array elements
     */
    public Type itemType;

    /**
     * The minimum number of items this array must always accommodate
     */
    public int minItems;

    /**
     * The maximum number of items this array must always accommodate
     */
    public int maxItems;

    /**
     * Simple array type constructor
     * @param itemType The type of the array elements
     */
    public ArrayType(Type parent, String id, Type itemType) {
        super(parent, id);
        this.itemType = itemType;
        this.minItems = -1;
        this.maxItems = -1;
    }

    /**
     * Simple array type constructor
     * @param itemTypeFuture The type of the array elements
     */
    public ArrayType(CompletableFuture<Type> parentFuture, String id, CompletableFuture<Type> itemTypeFuture) {
        super(parentFuture, id);
        itemTypeFuture.whenComplete((type, throwable) -> {
            this.itemType = type;
        });
        this.minItems = -1;
        this.maxItems = -1;
    }

    /**
     * A chainable call to define the maximum number of items
     * @param maxItems The maximum number of items to accommodate (or -1 for unspecified)
     */
    public ArrayType setMaxItems(int maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    /**
     * A chainable call to define the maximum number of items
     * @param minItems The minimum number of items to accommodate (or -1 for unspecified)
     */
    public ArrayType setMinItems(int minItems) {
        this.minItems = minItems;
        return this;
    }

    @Override
    public String toString() {
        return "ArrayType{" +
                "id=" + getId() +
                ", itemType=" + itemType +
                '}';
    }

    /**
     * Return the kind of this object as string
     * @return Returns `array`
     */
    @Override
    public String getTypeName() {
        return "array";
    }

    /**
     * Checks if this array type is equal to the given type
     * @param type The type to compare against
     * @return Returns true if the two types are equal
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof ArrayType)) return false;
        return ((ArrayType) type).itemType.equals(this.itemType);
    }

}
