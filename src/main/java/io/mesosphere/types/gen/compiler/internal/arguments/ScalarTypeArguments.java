package io.mesosphere.types.gen.compiler.internal.arguments;

import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;
import io.mesosphere.types.gen.models.internal.types.ScalarType;

/**
 * Compiler arguments interface for `ScalarType` classes
 */
public class ScalarTypeArguments extends TypeArguments {

    /**
     * Constructor for ArrayTypeArguments
     *
     * @param type   The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    ScalarTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Returns an interface to the storage type
     */
    public StorageArguments getStorage() {
        return new StorageArguments(((ScalarType)type.getStructural()).storageType, engine);
    }

}
