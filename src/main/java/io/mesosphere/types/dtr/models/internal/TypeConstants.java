package io.mesosphere.types.dtr.models.internal;

import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.types.AnnotativeType;
import io.mesosphere.types.dtr.models.internal.types.ScalarType;
import io.mesosphere.types.dtr.models.internal.values.AnyValue;
import io.mesosphere.types.dtr.models.internal.values.StringValue;

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
