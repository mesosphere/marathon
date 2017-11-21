package io.mesosphere.types.dtr.models.internal.values;

import io.mesosphere.types.dtr.models.internal.Value;

/**
 * A reference to any valid object, without other type details. Consider this
 * as a (void*) pointer to a type.
 *
 * Note that not all back-ends support this type, so try to avoid it as much
 * as possible.
 */
public class AnyValue extends Value.Dynamic {

    @Override
    public Integer getMinimumSize() {
        return 0;
    }

    /**
     * Return the name of the storage value
     * @return Returns "any"
     */
    @Override
    public String toName() {
        return "any";
    }

    @Override
    public Boolean equals(Value other) {
        return other instanceof AnyValue;
    }
}
