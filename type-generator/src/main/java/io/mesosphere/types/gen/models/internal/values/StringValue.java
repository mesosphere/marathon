package io.mesosphere.types.gen.models.internal.values;

import io.mesosphere.types.gen.models.internal.Value;

/**
 * A sequence of characters
 */
public class StringValue extends Value.Dynamic {

    private Integer minSize = 0;
    private Integer maxSize = null;

    /**
     * Constructor with size bounds
     * @param minSize The minimum size of the string
     * @param maxSize The maximum size of the string
     */
    public StringValue(Integer minSize, Integer maxSize) {
        this.minSize = minSize;
        this.maxSize = maxSize;
    }

    /**
     * Constructor without size bounds
     */
    public StringValue() {
        this.minSize = 0;
        this.maxSize = null;
    }

    @Override
    public Integer getMinimumSize() {
        return minSize;
    }

    @Override
    public Integer getMaximumSize() {
        return maxSize;
    }

    /**
     * Return the name of the storage value
     * @return Returns "string"
     */
    @Override
    public String toName() {
        return "string";
    }

    @Override
    public Boolean equals(Value other) {
        return other instanceof StringValue;
    }
}
