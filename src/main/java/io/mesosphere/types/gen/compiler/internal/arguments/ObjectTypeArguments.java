package io.mesosphere.types.gen.compiler.internal.arguments;

import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;
import io.mesosphere.types.gen.models.internal.types.ObjectType;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiler arguments interface for `ObjectType` classes
 */
public class ObjectTypeArguments extends TypeArguments {

    /**
     * Constructor for ObjectTypeArguments
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    ObjectTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Return the parent type
     */
    public TypeArguments getParent() {
        return TypeArguments.forType(((ObjectType)type.getStructural()).getParent(), scope, engine);
    }

    /**
     * @return Return an array with the properties of the object
     */
    public List<PropertyArguments> getProperties() {
        return ((ObjectType)type.getStructural()).properties.entrySet().stream()
                .map(p -> new PropertyArguments(scope, p.getKey(), p.getValue(), engine))
                .collect(Collectors.toList());
    }

}
