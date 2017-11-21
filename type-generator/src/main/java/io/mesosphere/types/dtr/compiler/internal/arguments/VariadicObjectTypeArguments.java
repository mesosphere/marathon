package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.TypeConstants;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.StructuralType;
import io.mesosphere.types.dtr.models.internal.types.VariadicObjectType;

/**
 * Compiler arguments interface for `VariadicObject` classes
 */
public class VariadicObjectTypeArguments extends ObjectTypeArguments {

    /**
     * Constructor for VariadicObjectType
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    VariadicObjectTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Returns the common type of all properties
     */
    public TypeArguments getItemType() {
        StructuralType struct = type.getStructural();
        Type variadicObjectType = ((VariadicObjectType)struct).variadicObjectType;

        // If the base object derives straight from the base variadic object
        // the `itemType` would be set to null. In this case, crate interface
        // for the widest possible type, aka `any`.
        if (variadicObjectType == null) {
            return TypeArguments.forType(TypeConstants.SCALAR_ANY, scope, engine);
        } else {
            return TypeArguments.forType(variadicObjectType, scope, engine);
        }
    }

}
