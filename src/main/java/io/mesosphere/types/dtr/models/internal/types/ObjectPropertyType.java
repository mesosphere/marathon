package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Object properties do not fall under the type tree
 */
public class ObjectPropertyType {

    /**
     * The regular expression for matching the property expression
     */
    public String key;

    /**
     * The type of the property
     */
    CompletableFuture<Type> typeFuture;

    /**
     * True if this property is required
     */
    public Boolean required;

    /**
     * A reference to the Type this property is inherited from
     */
    public Type inheritedFrom;

    /**
     * A complete constructor for the object property type
     * @param type The type of the property
     * @param key The key to the property (raw string or regex)
     * @param required True if this property is required
     * @param inheritedFrom A reference to the Type this property is inherited from
     */
    public ObjectPropertyType(Type type, String key, Boolean required, Type inheritedFrom) {
        this.typeFuture = CompletableFuture.completedFuture(type);
        this.key = key;
        this.required = required;
        this.inheritedFrom = inheritedFrom;
    }

    /**
     * A complete constructor for the object property type
     * @param typeFuture The future to the type of the property
     * @param key The key to the property (raw string or regex)
     * @param required True if this property is required
     * @param inheritedFrom A reference to the Type this property is inherited from
     */
    public ObjectPropertyType(CompletableFuture<Type> typeFuture, String key, Boolean required, Type inheritedFrom) {
        this.typeFuture = typeFuture;
        this.key = key;
        this.required = required;
        this.inheritedFrom = inheritedFrom;
    }

    /**
     * Try to resolve the type future (blocking) and return the type
     * @return Returns the type in the `typeFuture`
     */
    public Type getType() throws InterruptedException, ExecutionException, TimeoutException {
        return typeFuture.get(1, TimeUnit.SECONDS);
    }

    /**
     * Clones an instance of property type
     * @return Returns a new instance with the same variables
     */
    public ObjectPropertyType clone() {
        return new ObjectPropertyType(typeFuture, key, required, inheritedFrom);
    }
}
