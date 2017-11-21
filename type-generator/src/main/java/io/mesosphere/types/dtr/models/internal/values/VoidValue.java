package io.mesosphere.types.dtr.models.internal.values;

import io.mesosphere.types.dtr.models.internal.Value;

/**
 * A "Void" value has no size.
 */
public class VoidValue extends Value {

    /**
     * Return the name of the storage value
     * @return Returns "void"
     */
    @Override
    public String toName() {
        return "void";
    }

    @Override
    public Boolean equals(Value other) {
        return other instanceof VoidValue;
    }

}
