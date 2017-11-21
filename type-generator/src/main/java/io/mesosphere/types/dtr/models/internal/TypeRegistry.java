package io.mesosphere.types.dtr.models.internal;

import io.mesosphere.types.dtr.models.internal.types.ObjectType;
import io.mesosphere.types.dtr.models.internal.types.ScalarType;
import io.mesosphere.types.dtr.models.internal.values.NumberValue;
import io.mesosphere.types.dtr.models.internal.values.StringValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TypeRegistry {

    /**
     * A dictionary of all the defined types
     */
    public Map<String, Type> types = new HashMap<>();

    /**
     * Return a type reference by it's name
     * @param name The name of the type
     * @return
     */
    public Type get(String name) {
        if (!types.containsKey(name)) {
            return null;
        }

        return types.get(name);
    }

    /**
     * Store the specified type for later reference
     * @param name The name of the type
     * @param type The type object
     */
    public Type set(String name, Type type) {
        types.put(name, type);
        return type;
    }

    /**
     * Store the specified anonymous type for later reference
     */
    public Type add(Type type) {
        return type;
    }

}
