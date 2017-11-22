package io.mesosphere.types.gen.compiler.internal.arguments;

import io.mesosphere.types.gen.compiler.internal.BlockArguments;
import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;
import io.mesosphere.types.gen.models.internal.TypeScopeRelation;
import io.mesosphere.types.gen.models.internal.types.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Base compiler arguments interface for all `Type` classes
 */
public class TypeArguments implements BlockArguments {

    /**
     * The type we are interfacing
     */
    protected Type type;

    /**
     * The scope where this type was defined
     */
    protected TypeScope scope;

    /**
     * The engine that is going to compile the type
     */
    protected CompilerEngine engine;

    ////////////////////////////////////////////////////////////////////////////
    // Hierectary accessors and getters
    ////////////////////////////////////////////////////////////////////////////

    /**
     * @return Return the parent union relation type (if we are part of a union)
     */
    public TypeArguments getParentUnion() {
        Optional<TypeScopeRelation> unionRelation = scope
                .getRelationsOfKind(type, TypeScopeRelation.Relation.UnionOf)
                .findFirst();

        // Return null if this is not a union child
        if (!unionRelation.isPresent()) {
            return null;
        }

        // Otherwise resolve the union parent
        try {
            return TypeArguments.forType(
                unionRelation.get().target,
                scope,
                engine
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @return Return the base type relation type (if we are extending a type)
     */
    public TypeArguments getBaseType() {
        Optional<TypeScopeRelation> baseRelation = scope
                .getRelationsOfKind(type, TypeScopeRelation.Relation.ChildOf)
                .findFirst();

        // Return null if this is not a union child
        if (!baseRelation.isPresent()) {
            return null;
        }

        // Otherwise resolve the union parent
        try {
            return TypeArguments.forType(
                    baseRelation.get().target,
                    scope,
                    engine
            );
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * @return Returns true if this type is a union base
     */
    public Boolean isUnionBase() {
        return type.getStructural() instanceof UnionType;
    }

    /**
     * @return Returns true if this type is a child in a union
     */
    public Boolean isUnionChild() {
        return scope
            .getRelationsOfKind(type, TypeScopeRelation.Relation.UnionOf)
            .count() > 0;
    }

    /**
     * @return Returns true if other type extend from this type
     */
    public Boolean isExtendingBase() {
        return scope
            .getRelationsOfKind(type, TypeScopeRelation.Relation.ParentOf)
            .count() > 0;
    }

    /**
     * @return Returns true if this type is extending other type
     */
    public Boolean isExtended() {
        return scope
            .getRelationsOfKind(type, TypeScopeRelation.Relation.ChildOf)
            .count() > 0;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Annotative value getters
    ////////////////////////////////////////////////////////////////////////////

    /**
     * @return Returns the default value expression
     */
    public ValueArguments getDefaultValue() {
        return new ValueArguments(
                getAnnotativeValueOrDefault(AnnotativeType::getDefaultValue),
                type,
                scope,
                engine
        );
    }

    /**
     * @return Return the description of this type
     */
    public String getDescription() {
        return getAnnotativeValueOrDefault(AnnotativeType::getDescription);
    }

    /**
     * @return Returns the example as string
     */
    public String getExample() {
        return getAnnotativeValueOrDefault(AnnotativeType::getExample, "").toString();
    }

    /**
     * @return Returns the metadata map
     */
    public Map<String, Object> getMeta() {
        return getAnnotativeValueOrDefault(AnnotativeType::getAllMeta, new HashMap<>());
    }

    /**
     * @return Returns true if this type is a built-in type
     */
    public Boolean getIsBuiltin() {
        return getAnnotativeValueOrDefault(AnnotativeType::getBuiltin, false);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Type information
    ////////////////////////////////////////////////////////////////////////////

    /**
     * @return Returns the type ID of the structural type
     */
    public String getId() { return type.getStructural().getId(); }

    /**
     * @return Returns the immediate type ID
     */
    public String getTypeId() { return type.getId(); }

    /**
     * @return Returns the kind of this type (object, array, scalar) as string
     */
    public String getKind() {
        return type.getTypeName();
    }

    /**
     * @return Returns true if the value is scalar
     */
    public Boolean getIsScalar() {
        return type.getStructural() instanceof ScalarType;
    }

    /**
     * @return Returns true if the value is object
     */
    public Boolean getIsObject() {
        return type.getStructural() instanceof ObjectType;
    }

    /**
     * @return Returns true if the value is variadic object
     */
    public Boolean getIsVariadicObject() {
        return type.getStructural() instanceof VariadicObjectType;
    }

    /**
     * @return Returns true if the value is array
     */
    public Boolean getIsArray() {
        return type.getStructural() instanceof ArrayType;
    }

    /**
     * @return Returns true if the value is union
     */
    public Boolean getIsUnion() {
        return type.getStructural() instanceof UnionType;
    }

    /**
     * @return Returns true if the value is enum
     */
    public Boolean getIsenum() {
        return type.getStructural() instanceof EnumType;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Helpers
    ////////////////////////////////////////////////////////////////////////////

    /**
     * A shorthand to `getAnnotativeValueOrDefault` hat uses `null` as the default value
     *
     * @param getter The getter of an annotative type value
     * @param <T> The type of the annotative property value
     * @return Returns the value of the getter (if annotative) or the `null` otherwise
     */
    private <T> T getAnnotativeValueOrDefault(Function<AnnotativeType, T> getter) {
        return getAnnotativeValueOrDefault(getter, null);
    }

    /**
     * A helper function that checks if the type has an annotation and if yes, calls
     * the getter to return the annotative value. If the type is not annotative, it
     * returns the default value
     *
     * @param getter The getter of an annotative type value
     * @param defaultValue The default value if the type is missing
     * @param <T> The type of the annotative property value
     * @return Returns the value of the getter (if annotative) or the defaultValue otherwise
     */
    private <T> T getAnnotativeValueOrDefault(Function<AnnotativeType, T> getter, T defaultValue) {
        AnnotativeType aType = type.getAnnotative();
        if (aType == null) return defaultValue;
        return getter.apply(aType);
    }

    /**
     * Protected constructor so it can only be instantiated via the factory method
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    TypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        this.type = type;
        this.scope = scope;
        this.engine = engine;
    }

    /**
     * Create a new type arguments for the given type and engine
     * @param type The type for which to create the TypeArguments
     * @param engine The engine that is going to compile it
     * @return Returns the appropriate TypeArguments instance
     */
    public static TypeArguments forType(Type type, TypeScope scope, CompilerEngine engine) {

        // On invalid data, return null
        if (type == null) {
            return null;
        }

        // Compare structural types
        StructuralType sType = type.getStructural();

        if (sType instanceof ArrayType) {
            return new ArrayTypeArguments(type, scope, engine);
        } else if (sType instanceof EnumType) {
            return new EnumTypeArguments(type, scope, engine);
        } else if (sType instanceof VariadicObjectType) {
            return new VariadicObjectTypeArguments(type, scope, engine);
        } else if (sType instanceof ObjectType) {
            return new ObjectTypeArguments(type, scope, engine);
        } else if (sType instanceof UnionType) {
            return new UnionTypeArguments(type, scope, engine);
        } else if (sType instanceof ScalarType) {
            return new ScalarTypeArguments(type, scope, engine);
        }

        return null;
    }

}
