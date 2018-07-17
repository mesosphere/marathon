package io.mesosphere.types.gen.models.internal;

/**
 * Provides meta-information on how a particular value is stored in
 * memory or in a persistence layer.
 */
public abstract class Value {

    /**
     * A "Dynamic" specialisation denotes that the value is going
     * to calculate it's value at run-time and cannot be known in advance.
     *
     * In this case, the `getSize` method will return the minimum number
     * of bytes required to represent this value (usually the header or
     * the pointer size).
     */
    public abstract static class Dynamic extends Value {

        /**
         * The minimum number of bytes required to represent this value
         * @return
         */
        abstract public Integer getMinimumSize();

        /**
         * The maximum number of bytes required to represent this value
         * If not available, this method should return `null`;
         * @return
         */
        public Integer getMaximumSize() { return null; }
    }

    /**
     * Return the name of this value
     * @return
     */
    public abstract String toName();

    /**
     * Compare the given storage value to the given one and check
     * if they are compatible
     * @return True if values are the same
     */
    public abstract Boolean equals(Value other);

}
