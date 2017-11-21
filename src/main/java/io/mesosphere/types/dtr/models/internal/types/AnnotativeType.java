package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeValueValidation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * An annotation type "annotates" an existing type with some meta-information useful
 * for composing this type representation.
 */
public class AnnotativeType extends Type {

    /**
     * The annotation builder helps build annotation types as a single-line expressions
     * For example:
     *
     * Builder.forType(someTyoe)
     *  .setDefault(defaultValue)
     *  .get();
     */
    public static class Builder {

        private AnnotativeType type;

        /**
         * Create a builder for the given type
         * @param type The type to encapsulate
         * @param id The type ID for the newly created AnnotativeType
         * @return Returns a builder object
         */
        public static Builder forType(Type type, String id) {
            Builder builder = new Builder();
            builder.type = new AnnotativeType(type, id);
            return builder;
        }

        /**
         * Return the type
         * @return Returns the constructed AnnotativeType
         */
        public AnnotativeType get() {
            return type;
        }

        /**
         * Copy all values from the given annotative type
         */
        public Builder copyFrom(AnnotativeType ref) {
            // If we don't have any reference ignore this command
            if (ref == null) return this;

            // Copy scalars
            type.defaultValue = ref.defaultValue;
            type.description = ref.description;
            type.example = ref.example;
            type.builtin = ref.builtin;
            type.required = ref.required;
            type.uniqueItems = ref.uniqueItems;
            type.minItems = ref.minItems;
            type.maxItems = ref.maxItems;
            type.additionalProperties = ref.additionalProperties;
            type.minProperties = ref.minProperties;
            type.maxProperties = ref.maxProperties;
            type.parentUnion = ref.parentUnion;

            // Import value validation
            type.valueValidation.clear();
            type.valueValidation.addAll(ref.valueValidation);

            // Import metadata
            type.metadata.clear();
            type.metadata.putAll(ref.metadata);

            return this;
        }

        /**
         * Set the default value for this type
         * @param value The default value
         * @return Returns the builder so the calls can be chained
         */
        public Builder setDefaultValue(Object value) {
            type.defaultValue = value;
            return this;
        }

        /**
         * Set the description for this type
         * @param value The description
         * @return Returns the builder so the calls can be chained
         */
        public Builder setDescription(String value) {
            type.description = value;
            return this;
        }

        /**
         * Set the example(s) object for this type
         * @param value The example object
         * @return Returns the builder so the calls can be chained
         */
        public Builder setExample(Object value) {
            type.example = value;
            return this;
        }

        /**
         * Set the value for the given metadata field
         * @param name The name of the metadata field
         * @param value The metadata value
         * @return Returns the builder so the calls can be chained
         */
        public Builder putMeta(String name, Object value) {
            type.metadata.put(name, value);
            return this;
        }

        /**
         * Sets the built-in flag for the type
         * @param isSet The value for the flag
         * @return Returns the builder so the calls can be chained
         */
        public Builder setBuiltin(Boolean isSet) {
            type.builtin = isSet;
            return this;
        }

        /**
         * Sets the required flag for the type
         * @param isSet The value for the flag
         * @return Returns the builder so the calls can be chained
         */
        public Builder setRequired(Boolean isSet) {
            type.required = isSet;
            return this;
        }

        /**
         * Sets the unique items flag for the type
         * @param isSet The value for the flag
         * @return Returns the builder so the calls can be chained
         */
        public Builder setUniqueItems(Boolean isSet) {
            type.uniqueItems = isSet;
            return this;
        }

        /**
         * Sets the minItems property for the type
         * @param value The value for the property
         * @return Returns the builder so the calls can be chained
         */
        public Builder setMinItems(Integer value) {
            type.minItems = value;
            return this;
        }

        /**
         * Sets the maxItems property for the type
         * @param value The value for the property
         * @return Returns the builder so the calls can be chained
         */
        public Builder setMaxItems(Integer value) {
            type.maxItems = value;
            return this;
        }

        /**
         * Sets the `additional properties` flag for the type
         * @param isSet The value for the flag
         * @return Returns the builder so the calls can be chained
         */
        public Builder setAdditionalProperties(Boolean isSet) {
            type.additionalProperties = isSet;
            return this;
        }

        /**
         * Sets the minProperties property for the type
         * @param value The value for the property
         * @return Returns the builder so the calls can be chained
         */
        public Builder setMinProperties(Integer value) {
            type.minProperties = value;
            return this;
        }

        /**
         * Sets the maxProperties property for the type
         * @param value The value for the property
         * @return Returns the builder so the calls can be chained
         */
        public Builder setMaxProperties(Integer value) {
            type.maxProperties = value;
            return this;
        }

        /**
         * Sets the parentUnion property for the type
         * @param future The future that will resolve to the union type
         * @return Returns the builder so the calls can be chained
         */
        public Builder setParentUnionFuture(CompletableFuture<Type> future) {
            future.whenComplete((unionType, throwable) -> {
                if (throwable != null) return;
                type.parentUnion = (UnionType) unionType;
            });
            return this;
        }

        /**
         * Appends the type value validation to use
         * @param value The instance of the TypeValueValidation to use
         * @return Returns the builder so the calls can be chained
         */
        public Builder addValueValidation(TypeValueValidation value) {
            type.valueValidation.add(value);
            return this;
        }


    }

    private Object defaultValue = null;

    private String description = "";

    private Object example = null;

    private Map<String, Object> metadata = new HashMap<>();

    private Boolean builtin = false;

    private List<TypeValueValidation> valueValidation = new ArrayList<>();

    private Boolean required = null;

    /*
     * Array Type
     * ===================
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#array-type
     */

    /**
     * If true the array items must be unique
     */
    private Boolean uniqueItems = null;

    /**
     * Minimum items in the array (default from spec)
     */
    private Integer minItems = 0;

    /**
     * Maximum items in the array (default from spec)
     */
    private Integer maxItems = 2147483647;

    /*
     * Object Type
     * ===================
     * https://github.com/raml-org/raml-spec/blob/master/versions/raml-10/raml-10.md#object-type
     */

    /**
     * Accept additional properties in the object
     */
    private Boolean additionalProperties = true;

    /**
     * Minimum number of properties in the object
     */
    private Integer minProperties = null;

    /**
     * Maximum number of properties in the object
     */
    private Integer maxProperties = null;

    /**
     * If this object belongs in a union this will point to the parent union
     */
    private UnionType parentUnion = null;

    /**
     * Default constructor is private
     * @param parent The parent type
     * @param id The annotation ID
     */
    private AnnotativeType(Type parent, String id) {
        super(parent, id);
    }

    /**
     * Return the type name of the parent structural type
     * @return
     */
    @Override
    public String getTypeName() {
        return getStructuralParent().getTypeName();
    }

    /**
     * @return Returns the default value
     */
    public Object getDefaultValue() {
        return defaultValue;
    }

    /**
     * @return Returns the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return Returns the example
     */
    public Object getExample() {
        return example;
    }

    /**
     * @return Returns true if this type is required in a composition
     */
    public Boolean getRequired() {
        return required;
    }

    /**
     * Return the metadata value or the given default
     * @param name The name of the metadata to return
     * @param defaultValue The default value to return when the variable is missing
     * @return Returns the metadata value or the provided default
     */
    public Object getMetaOrDefault(String name, Object defaultValue) {
        return metadata.getOrDefault(name, defaultValue);
    }

    /**
     * Return the entire map of metadata
     * @return Returns a map with all the metadata in the annotation
     */
    public Map<String, Object> getAllMeta() {
        return metadata;
    }

    /**
     * @return Returns true if this type is built-in
     */
    public Boolean isBuiltin() {
        return builtin;
    }

    public Boolean getBuiltin() { return builtin; }

    public Boolean getUniqueItems() { return uniqueItems; }

    public Integer getMinItems() { return minItems; }

    public Integer getMaxItems() { return maxItems; }

    public Boolean getAdditionalProperties() { return additionalProperties; }

    public Integer getMinProperties() { return minProperties; }

    public Integer getMaxProperties() { return maxProperties; }

    public UnionType getParentUnion() { return parentUnion; }

    /**
     * @return Returns the value validation
     */
    public List<TypeValueValidation> getValueValidation() {
        return valueValidation;
    }

    /**
     * Check if the structural types of this annotative type equals to each other
     * @param o The type to check
     * @return Returns true if the two types match
     */
    @Override
    public Boolean equals(Type o) {
        return getStructural().equals(o.getStructural());
    }

    @Override
    public String toString() {
        String desc = "@{description=\"" + description + "\"";

        if (defaultValue != null) desc += ", defaultValue=" + defaultValue;
        if (example != null) desc += ", example=" + example;
        if (metadata != null) desc += ", metadata=" + metadata;
        if (builtin != null) desc += ", builtin=" + builtin;
        if (valueValidation != null) desc += ", valueValidation=" + valueValidation;
        if (required != null) desc += ", required=" + required;
        if (uniqueItems != null) desc += ", uniqueItems=" + uniqueItems;
        if (minItems != null) desc += ", minItems=" + minItems;
        if (maxItems != null) desc += ", maxItems=" + maxItems;
        if (additionalProperties != null) desc += ", additionalProperties=" + additionalProperties;
        if (minProperties != null) desc += ", minProperties=" + minProperties;
        if (maxProperties != null) desc += ", maxProperties=" + maxProperties;
        if (parentUnion != null) desc += ", parentUnion=" + parentUnion;

        return desc + "(" + getParent() + ")}";
    }
}
