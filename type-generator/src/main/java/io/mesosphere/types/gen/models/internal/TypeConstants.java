package io.mesosphere.types.gen.models.internal;

import io.mesosphere.types.gen.models.internal.types.AnnotativeType;
import io.mesosphere.types.gen.models.internal.types.ScalarType;
import io.mesosphere.types.gen.models.internal.values.AnyValue;

public class TypeConstants {

    /**
     * Static type that resolves to "any"
     */
    public static final Type  SCALAR_ANY = AnnotativeType.Builder
        .forType(new ScalarType((Type)null, "any", new AnyValue()), "any")
        .setBuiltin(true)
        .setDefaultValue(null)
        .setDescription("A pointer (any) type")
        .get();

}
