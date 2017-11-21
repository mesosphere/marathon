package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.BlockArguments;
import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.ObjectPropertyType;
import io.mesosphere.types.dtr.models.internal.types.ObjectType;

/**
 * Compiler arguments interface for `io.mesosphere.types.dtr.models.internal.types.ObjectPropertyType` classes
 */
public class PropertyArguments implements BlockArguments {

    /**
     * The property we are interfacing for
     */
    private ObjectPropertyType property;

    /**
     * The scope where this property was defined
     */
    private TypeScope scope;

    /**
     * The property name
     */
    private String name;

    /**
     * The engine that is going to compile the type
     */
    private CompilerEngine engine;

    /**
     * Constructor
     * @param name The name of the property
     * @param property The property for which to to create an interface
     * @param engine The engine that is going to compile the type
     */
    public PropertyArguments(TypeScope scope, String name, ObjectPropertyType property, CompilerEngine engine) {
        this.scope = scope;
        this.name = name;
        this.property = property;
        this.engine = engine;
    }

    /**
     * @return Return the name of the property
     */
    public String getName() {
        return name;
    }

    /**
     * @return Return the type of the property
     */
    public TypeArguments getType() {
        try {
            return TypeArguments.forType(property.getType(), scope, engine);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @return Return true if this property is required
     */
    public Boolean getRequired() {
        return property.required;
    }

    /**
     * @return Return true if this property is inherited
     */
    public Boolean getInherited() { return property.inheritedFrom != null; }

}
