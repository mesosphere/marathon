package io.mesosphere.types.gen.models.internal.types;

import io.mesosphere.types.gen.models.internal.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * An object type contains one or more properties with the specified name and type
 */
public class ObjectType extends StructuralType {

    /**
     * The parent union in case this object is part of a union
     */
    public UnionType parentUnion;

    /**
     * The object properties and their type
     */
    public Map<String, ObjectPropertyType> properties;

    /**
     * When this object is used in a union, this variable contains the value
     * of the discriminator property.
     */
    public String discriminatorValue;

    /**
     * The name of the discriminator property .
     */
    public String discriminatorProperty;

    /**
     * Create an named object with the specified parent type and name
     * @param parentType The base type to extend or `null` if we define a base type
     * @param id The name of this type
     */
    public ObjectType(Type parentType, String id) {
        super(parentType, id);
        this.parentUnion = null;
        this.properties = new HashMap<>();
        this.discriminatorProperty = null;
        this.discriminatorValue = id;
    }

    /**
     * Create a named object with the specified parent type, name and properties
     * @param parentType The base type to extend or `null` if we define a base type
     * @param id The name of this type
     * @param properties The properties of the new object
     */
    public ObjectType(Type parentType, String id, Map<String, ObjectPropertyType> properties) {
        super(parentType, id);
        this.parentUnion = null;
        this.properties = properties;
        this.discriminatorProperty = null;
        this.discriminatorValue = id;
    }

    /**
     * Create a named object with the specified parent type, name and properties
     * @param parentTypeFuture The base type to extend or `null` if we define a base type
     * @param id The name of this type
     * @param properties The properties of the new object
     */
    public ObjectType(CompletableFuture<Type> parentTypeFuture, String id, Map<String, ObjectPropertyType> properties) {
        super(parentTypeFuture, id);
        this.parentUnion = null;
        this.properties = properties;
        this.discriminatorProperty = null;
        this.discriminatorValue = id;
    }

    /**
     * Sets the new value for the discriminatorValue property
     * @param discriminatorValue The value to set
     * @return Returns `this` in order to make this call chainable
     */
    public ObjectType setDiscriminatorValue(String discriminatorValue) {
        this.discriminatorValue = discriminatorValue;
        return this;
    }

    /**
     * Sets the new value for the discriminatorProperty property
     * @param discriminatorProperty The value to set
     * @return Returns `this` in order to make this call chainable
     */
    public ObjectType setDiscriminatorProperty(String discriminatorProperty) {
        this.discriminatorProperty = discriminatorProperty;
        return this;
    }

    /**
     * Returns a cloned object type, extended with the properties provided
     *
     * @param id The ID of the created object
     * @param properties The properties to append
     * @return Returns the cloned object with the properties extended
     */
    public ObjectType extendClone(String id, Map<String, ObjectPropertyType> properties) {

        // Create a new array of properties
        Map<String, ObjectPropertyType> props = new HashMap<>();

        // Annotate all our current properties as inherited
        for (Map.Entry<String, ObjectPropertyType> p: this.properties.entrySet()) {
            props.put(
                p.getKey(),
                new ObjectPropertyType(
                    p.getValue().typeFuture,
                    p.getValue().key,
                    p.getValue().required,
                    this
                )
            );
        }

        // Extend with the provided properties
        props.putAll(properties);

        // Create new object type
        return new ObjectType(this, id, props);
    }

//    /**
//     * Create a new class that copies all the properties of the current class
//     * and further extends it with the properties specified.
//     */
//    public ObjectType extend(Map<String, Property> properties) {
//        ObjectType type = (ObjectType) this.clone();
//        type.parentType = this;
//        type.properties.putAll(properties);
//        return type;
//    }

    @Override
    public String toString() {
        return getTypeName() + "{" +
                "id=" + getId() +
                ", parentType=" + getStructuralParent() +
                ", properties=" + properties +
                '}';
    }

    /**
     * Return the kind of this object as string
     * @return Returns `object`
     */
    @Override
    public String getTypeName() {
        return "object";
    }

    /**
     * Checks if this object type is equal to the given type by checking
     * each individual property.
     *
     * @param type The type to compare against
     * @return Returns true if the two types are equal
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof ObjectType)) return false;
        ObjectType otype = (ObjectType) type;

        // Check if property count is wrong
        if (otype.properties.size() != properties.size()) return false;

        // Check if all properties match
        for (Map.Entry<String, ObjectPropertyType> p: properties.entrySet()) {
            if (!otype.properties.containsKey(p.getKey())) return false;
            ObjectPropertyType oprop = otype.properties.get(p.getKey());
            if (oprop.required != p.getValue().required) return false;
            try {
                if (!oprop.getType().equals(p.getValue().getType())) return false;
            } catch (Exception e) {
                // Cannot compare types that are not resolved yet
                // TODO: Throw an exception here instead of failing silently
                return false;
            }
        }

        // Looks same
        return true;
    }

}
