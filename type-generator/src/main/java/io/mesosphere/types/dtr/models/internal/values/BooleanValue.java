package io.mesosphere.types.dtr.models.internal.values;

import io.mesosphere.types.dtr.models.internal.Value;

public class BooleanValue extends Value {

    /**
     * Return the name of the storage value
     * @return Returns "boolean"
     */
    @Override
    public String toName() {
        return "boolean";
    }

    @Override
    public Boolean equals(Value other) {
        return other instanceof BooleanValue;
    }
}
