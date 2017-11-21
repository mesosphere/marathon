package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.EnumType;
import io.mesosphere.types.dtr.models.internal.types.ScalarType;
import io.mesosphere.types.dtr.models.internal.types.UnionType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiler arguments interface for `EnumType` classes
 */
public class EnumTypeArguments extends TypeArguments {

    /**
     * Constructor for ArrayTypeArguments
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    EnumTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Return the type of the enum values
     */
    public TypeArguments getItemType() {
        return TypeArguments.forType(((EnumType)type.getStructural()).itemType, scope, engine);
    }

    /**
     * @return Return the expressions of all values
     */
    public List<ValueArguments> getValues() {
        return ((EnumType)type.getStructural()).values
                .stream()
                .map(o -> new ValueArguments(o, ((EnumType) type.getStructural()).itemType, scope, engine))
                .collect(Collectors.toList());
    }

}
