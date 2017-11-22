package io.mesosphere.types.gen.models.internal.values;

import io.mesosphere.types.gen.models.internal.Value;

/**
 * A numeric representation
 */
public class NumberValue extends Value {

    /**
     * Set to `true` if this type is signed
     */
    public Boolean isSigned;

    /**
     * Set to `true` if this type is integer
     */
    public Boolean isInteger;

    /**
     * Set to `true` if this type is short (Ex. 4-byte long)
     */
    public Size size;

    /**
     * The size of this numerical value
     */
    public enum Size {
        SHORT,   // Usually 1-byte
        DEFAULT, // Usually 2-byte
        LONG,    // Usually 4-byte
        DOUBLE   // Usually 8-byte
    }

    /**
     * Descriptive constructor of number
     * @param isInteger True if this type is integer
     * @param isSigned True if this type is signed
     * @param size The size of the number (Ex. short, long, double)
     */
    public NumberValue(Boolean isInteger, Boolean isSigned, Size size) {
        this.isSigned = isSigned;
        this.isInteger = isInteger;
        this.size = size;
    }

    /**
     * Shorthand constructor for number that defaults to signed, non-short, non-double numbers
     * @param isInteger True if this type is integer
     */
    public NumberValue(Boolean isInteger) {
        this.isSigned = true;
        this.isInteger = isInteger;
        this.size = Size.DEFAULT;
    }

    /**
     * Return the name of the storage value
     * @return Returns "number"
     */
    @Override
    public String toName() {
        if (isInteger) return "integer";
        return "number";
    }

    @Override
    public Boolean equals(Value other) {
        if (!(other instanceof NumberValue)) return false;
        if (((NumberValue) other).isInteger != this.isInteger) return false;
        return true;
    }
}
