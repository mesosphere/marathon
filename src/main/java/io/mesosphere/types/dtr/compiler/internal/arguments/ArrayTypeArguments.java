package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.ArrayType;

/**
 * Compiler arguments interface for `ArrayType` classes
 */
public class ArrayTypeArguments extends TypeArguments {

    /**
     * Constructor for ArrayTypeArguments
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    ArrayTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Return the type of the items
     */
    public TypeArguments getItemType() {
        return TypeArguments.forType(((ArrayType)type.getStructural()).itemType, scope, engine);
    }

    /**
     * @return Return the maximum number of items in the array type
     */
    public Integer getMaxItems() {
        return((ArrayType)type.getStructural()).maxItems;
    }

    /**
     * @return Return the minimum number of items in the array type
     */
    public Integer getMinItems() {
        return((ArrayType)type.getStructural()).minItems;
    }

}
