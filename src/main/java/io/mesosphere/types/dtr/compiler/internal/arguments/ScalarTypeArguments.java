package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.ScalarType;

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
