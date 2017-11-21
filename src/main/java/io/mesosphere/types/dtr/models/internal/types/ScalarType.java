package io.mesosphere.types.dtr.models.internal.types;

import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.Value;
import io.mesosphere.types.dtr.models.internal.values.*;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * A scalar type is the one actually stored
 */
public class ScalarType extends StructuralType {

    /**
     * Detailed information about how this type is stored
     */
    public Value storageType;

    /**
     * Scalar type constructor with
     * @param parent The parent type
     * @param id The name of the object
     * @param storageType
     */
    public ScalarType(Type parent, String id, Value storageType) {
        super(parent, id);
        this.storageType = storageType;
    }

    /**
     * Scalar type constructor with
     * @param parentFuture The future to the parent type
     * @param id The name of the object
     * @param storageType
     */
    public ScalarType(CompletableFuture<Type> parentFuture, String id, Value storageType) {
        super(parentFuture, id);
        this.storageType = storageType;
    }

    /**
     * Guess the scalar type by the value given
     * @param value The value to guess
     * @return Returns the instance of type
     */
    public static ScalarType guessByValue(Object value) {
        if (value == null) {
            return new ScalarType((Type)null, "void", new VoidValue());
        } else if (value instanceof Integer) {
            return new ScalarType((Type)null, "integer", new NumberValue(true));
        } else if (value instanceof Number) {
            return new ScalarType((Type)null, "number", new NumberValue(false));
        } else if (value instanceof Boolean) {
            return new ScalarType((Type)null, "boolean", new BooleanValue());
        } else if (value instanceof Date || value instanceof TemporalAccessor) {
            return new ScalarType((Type)null, "datetime", new DateTimeValue(true, true, true));
        } else if (value instanceof String) {
            return new ScalarType((Type)null, "string", new StringValue());
        } else {
            return new ScalarType((Type)null, "any", new AnyValue());
        }
    }

    @Override
    public String toString() {
        return "ScalarType{" +
                "storageType=" + storageType +
                ", id='" + getId() + '\'' +
                '}';
    }

    /**
     * Return the kind of this object as string
     * @return Returns `scalar`
     */
    @Override
    public String getTypeName() {
        return "scalar";
    }

    /**
     * Return true if the type given is scalar and has the same storage type
     * @param type The type to compare against
     * @return
     */
    @Override
    public Boolean equals(Type type) {
        if (!(type instanceof ScalarType)) return false;
        return storageType.equals(((ScalarType) type).storageType);
    }

}
