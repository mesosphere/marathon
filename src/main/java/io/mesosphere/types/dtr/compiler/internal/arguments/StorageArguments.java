package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.BlockArguments;
import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Value;

/**
 * Storage arguments provides an interface for the storage value of scalar types
 */
public class StorageArguments implements BlockArguments {

    private Value storageType;

    private CompilerEngine engine;

    /**
     * Constructor for the given storage value
     * @param storageType The storage type of a scalar value
     * @param engine The engine that is going to compile this
     */
    public StorageArguments(Value storageType, CompilerEngine engine) {
        this.storageType = storageType;
        this.engine = engine;
    }

    /**
     * @return Return the kind of the storage value
     */
    public String getKind() {
        return storageType.toName();
    }


    /**
     * @returns Returns the language-specific expression that represents the storage type
     */
    public String getExpr() {
        return engine.getStorageTypeName(storageType);
    }

    /**
     * By default, the `toString` evaluation will return the type expression
     * @return Returns the language-specific expression that represents the storage type
     */
    @Override
    public String toString() {
        return getExpr();
    }
}
