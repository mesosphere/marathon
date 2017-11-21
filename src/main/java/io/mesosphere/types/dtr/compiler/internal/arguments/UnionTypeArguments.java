package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeConstants;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.types.*;
import io.mesosphere.types.dtr.models.internal.values.AnyValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Compiler arguments interface for `UnionType` classes
 */
public class UnionTypeArguments extends TypeArguments {

    /**
     * Constructor for ObjectTypeArguments
     * @param type The type we are interfacing
     * @param engine The engine that is going to compile the type
     */
    UnionTypeArguments(Type type, TypeScope scope, CompilerEngine engine) {
        super(type, scope, engine);
    }

    /**
     * @return Return the union types that compose this union
     */
    public List<TypeArguments> getUnionTypes() {
        return ((UnionType)type).unionTypes.stream()
                .map(type1 -> TypeArguments.forType(type1, scope, engine))
                .collect(Collectors.toList());
    }

    /**
     * Compute and return the biggest subset of properties of all union types
     * @return
     */
    public List<PropertyArguments> getMostSharedProperties() throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, ObjectPropertyType> args = new HashMap<>();

        // Iterate over the properties of the union types and collect the
        // biggest overlapping set of properties
        for (Type u: ((UnionType)type.getStructural()).unionTypes) {
            StructuralType utype = u.getStructural();
            Map<String, ObjectPropertyType> objectProps = null;

            if (utype instanceof ObjectType) {
                objectProps = ((ObjectType) utype).properties;
            } else if (utype instanceof VariadicObjectType) {
                objectProps = ((VariadicObjectType) utype).properties;
            }

            if (objectProps == null) continue;

            // Collect all properties matched and broaden the type of properties
            // under the same key that mix types to `any`
            for (Map.Entry<String, ObjectPropertyType> p: objectProps.entrySet()) {
                if (args.containsKey(p.getKey())) {
                    ObjectPropertyType commonType = args.get(p.getKey());

                    // If we already have "any" as common type for this property, ignore
                    if (commonType.getType().equals(TypeConstants.SCALAR_ANY)) {
                        continue;
                    }

                    // If the types mismatch, broaden the type of the variable into
                    // the most abstract type -> any
                    else if (!commonType.getType().equals(p.getValue().getType())) {
                        args.put(p.getKey(), new ObjectPropertyType(
                            TypeConstants.SCALAR_ANY,
                            p.getValue().key,
                            p.getValue().required,
                            p.getValue().inheritedFrom
                        ));
                    }
                } else {
                    args.put(p.getKey(), p.getValue());
                }
            }
        }

        // Cast into property arguments
        return args.entrySet().stream()
                .map(p -> new PropertyArguments(scope, p.getKey(), p.getValue(), engine))
                .collect(Collectors.toList());
    }

    /**
     * Compute and return the smallest subset of properties of all union types
     * @return
     */
    public List<PropertyArguments> getLeastSharedProperties() throws InterruptedException, ExecutionException, TimeoutException {
        Map<String, ObjectPropertyType> args = new HashMap<>();
        Boolean firstPass = true;

        // Iterate over the properties of the union types and collect the
        // smallest overlapping set of properties
        for (Type u: ((UnionType)type.getStructural()).unionTypes) {
            StructuralType utype = u.getStructural();
            Map<String, ObjectPropertyType> objectProps = null;

            if (utype instanceof ObjectType) {
                objectProps = ((ObjectType) utype).properties;
            } else if (utype instanceof VariadicObjectType) {
                objectProps = ((VariadicObjectType) utype).properties;
            }

            if (objectProps == null) continue;

            // Collect the smallest possible subset of properties and broaden the type
            // of properties under the same key that mix types to `any`
            if (firstPass) {
                args.putAll(objectProps);
                firstPass = false;
            } else {
                for (Map.Entry<String, ObjectPropertyType> p: objectProps.entrySet()) {
                    if (!args.containsKey(p.getKey())) continue;;
                    ObjectPropertyType commonType = args.get(p.getKey());

                    // If we already have "any" as common type for this property, ignore
                    if (commonType.getType().equals(TypeConstants.SCALAR_ANY)) {
                        continue;
                    }

                    // If the types mismatch, broaden the type of the variable into
                    // the most abstract type -> any
                    else if (!commonType.getType().equals(p.getValue().getType())) {
                        args.put(p.getKey(), new ObjectPropertyType(
                            TypeConstants.SCALAR_ANY,
                            p.getValue().key,
                            p.getValue().required,
                            p.getValue().inheritedFrom
                        ));
                    }

                }
            }
        }

        // Cast into property arguments
        return args.entrySet().stream()
                .map(p -> new PropertyArguments(scope, p.getKey(), p.getValue(), engine))
                .collect(Collectors.toList());
    }
}
