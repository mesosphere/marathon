package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * A "Variadic Object" is an object that contains an arbitrary number of fields that
 * cannot be pre-emtively put into discreet fields. Such objects are usually implemented
 * as `maps` or `dictionaries` for various language implementations.
 */
public class VariadicObjectType extends StructuralType {

    /**
     * A list of properties
     */
    public Map<String, ObjectPropertyType> properties;

    /**
     * Variadic objects should have properties that all share
     * the same type. Otherwise it's going to be quite complicated
     * for the parsers to de-compose them.
     *
     * TODO: This is a non-RAML standard, perhaps revise?
     */
    public Type variadicObjectType;

    /**
     * Variadic object constructor for pre-defined types without properties
     *
     * @param parent The super-type of this object
     * @param id The name of the object
     */
    public VariadicObjectType(Type parent, String id) {
        super(parent, id);
        this.properties = new HashMap<>();
        this.variadicObjectType = TypeConstants.SCALAR_ANY;
    }

    /**
     * Variadic object constructor for pre-defined types
     *
     * @param parent The super-type of this object
     * @param id The name of the object
     * @param properties The properties of the object from which to extract the shared type
     */
    public VariadicObjectType(Type parent, String id, Map<String, ObjectPropertyType> properties) {
        super(parent, id);
        this.properties = properties;

        // Wait until types are resolved and then detect the common type
        this.variadicObjectType = null;
        getCommonPropertyType(properties).whenComplete((type, throwable) -> {
            this.variadicObjectType = type;
        });
    }

    /**
     * Variadic object constructor
     * @param parentFuture The super-type of this object
     * @param id The name of the object
     * @param properties The properties of the object from which to extract the shared type
     */
    public VariadicObjectType(CompletableFuture<Type> parentFuture, String id, Map<String, ObjectPropertyType> properties) {
        super(parentFuture, id);
        this.properties = properties;

        // Wait until types are resolved and then detect the common type
        this.variadicObjectType = null;
        getCommonPropertyType(properties).whenComplete((type, throwable) -> {
            this.variadicObjectType = type;
        });
    }

    /**
     * Create a clone of the VariadicObjectType that is extended with the given properties
     * @param id The ID of the extended object
     * @param properties The properties of the extended object
     * @return Returns a new instance of VariadicObjectType with the new properties
     */
    public VariadicObjectType extendClone(String id, Map<String, ObjectPropertyType> properties) {
        Map<String, ObjectPropertyType> newProps = new HashMap<>();

        // Mark all of my properties as inherited
        for (Map.Entry<String, ObjectPropertyType> t: this.properties.entrySet()) {
            newProps.put(t.getKey(), new ObjectPropertyType(
                t.getValue().typeFuture,
                t.getValue().key,
                t.getValue().required,
                this
            ));
        }

        // Add new properties
        newProps.putAll(properties);

        // Create the new variadic object
        return new VariadicObjectType(
            this,
            id,
            newProps
        );
    }

    /**
     * Return the kind of this object as string
     * @return Returns `variadic_object`
     */
    @Override
    public String getTypeName() {
        return "variadic_object";
    }

    /**
     * Check if the two types are the same
     * @param type The type to compare against
     * @return Returns true if the class is the same and the properties are the same
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof VariadicObjectType)) return false;
        VariadicObjectType otype = (VariadicObjectType) type;

        // Check variadic object type
        if (!otype.variadicObjectType.equals(variadicObjectType)) return false;

        // Looks same
        return true;
    }

    @Override
    public String toString() {
        return getTypeName() + "{" +
                "id=" + getId() +
                ", parentType=" + getStructuralParent() +
                ", itemType=" + (variadicObjectType == null ? "<null>" : variadicObjectType.toString()) +
                '}';
    }

    /**
     * Convert an object type to variadic object type
     * @param type The base type
     * @return Returns the VariadicObjectType, created from the given object type
     */
    public static VariadicObjectType fromObjectType(ObjectType type) {
        return new VariadicObjectType(
            type.getParent(),
            type.getId(),
            type.properties
        );
    }

    /**
     * Iterate over the given map of properties and check if they all share
     * the same type. If yes, return that type, otherwise return null.
     *
     * @param properties The map of properties to check
     * @return Returns `null` if properties are different or the common property type
     */
    private CompletableFuture<Type> getCommonPropertyType(Map<String, ObjectPropertyType> properties) {
        return getCommonPropertyType(properties, CompletableFuture.completedFuture(null));
    }

    /**
     * Iterate over the given map of properties and check if they all share
     * the same type. If yes, return that type, otherwise return null.
     *
     * @param properties The map of properties to check
     * @param startingTypeFuture The first type to match everything else against
     * @return Returns `null` if properties are different or the common property type
     */
    private CompletableFuture<Type> getCommonPropertyType(Map<String, ObjectPropertyType> properties, CompletableFuture<Type> startingTypeFuture) {
        final CompletableFuture<Type> future = new CompletableFuture<>();

        // Wait for the starting type to be defined
        startingTypeFuture.whenComplete((startingType, throwable) -> {
            if (throwable != null) {
                future.completeExceptionally(throwable);
                return;
            }

            // Wait for the futures of all properties to complete
            io.mesosphere.types.dtr.utils.Futures.waitForAll(
                    properties.values().stream()
                            .map(objectPropertyType -> objectPropertyType.typeFuture)
                            .collect(Collectors.toList())
            ).whenComplete((types, throwable1) -> {
                if (throwable1 != null) {
                    future.completeExceptionally(throwable1);
                    return;
                }

                // Check if all types are equal
                Type commonType = startingType;
                for (Type type: types) {

                    // Set the first value
                    if (commonType == null) {
                        commonType = type;
                    }

                    // If the common type is already `any` we cannot get broader,
                    // complete the future right away
                    else if (commonType == TypeConstants.SCALAR_ANY) {
                        future.complete(commonType);
                        return;
                    }

                    // If there is no common type, fall back to "any"
                    else if (!commonType.equals(type)) {
                        future.complete(TypeConstants.SCALAR_ANY);
                        return;
                    }
                }

                // Otherwise complete the future with the common type
                future.complete(commonType);
            });
        });

        // Return the future that will resolve to the common type
        return future;
    }

}
