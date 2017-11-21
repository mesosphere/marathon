package io.mesosphere.types.dtr.compiler.internal;

import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;
import io.mesosphere.types.dtr.models.internal.Value;

/**
 * A compiler engine is the logic that converts a template name + macro values into
 * the final string, according to the engine specifications.
 */
public interface CompilerEngine {

    /**
     * Return the implementation-dependent name of the storage type
     * </p>
     * For example `StringValue` would be `String` in Java
     * </p>
     * Note that array types or other more complicated structures are constructed
     * through the engine templates and we don't need to provide such functionality here.
     *
     * @param storageType The internal storage type for which to get a name
     * @return Returns a string with the name of the storage type in the user-defined implementation
     */
    String getStorageTypeName(Value storageType);

    /**
     * Return the expression that describes the value of the object provided
     * Note that only numbers, strings and booleans should reach this function. An
     * IllegalArgumentException should be thrown otherwise.
     *
     * @param value The value to get an expression for
     * @param type The type to use for composing the value
     * @return The expression in the user-defined implementation
     * @throws IllegalArgumentException when the value is not a scalar
     */
    String getValueExpression(Object value, Type type, TypeScope scope) throws IllegalArgumentException;

    /**
     * Return a compiled fragment that is not type-dependent
     *
     * @param fragment The type of the compiled fragment to generate
     * @return Returns the compiled statement instance
     */
    CompiledFragment getCompiledFragment(CompiledFragmentType fragment) throws Exception;

}
