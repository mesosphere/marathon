package io.mesosphere.types.gen.compiler.internal.arguments;

import io.mesosphere.types.gen.compiler.internal.BlockArguments;
import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;

import java.util.regex.Pattern;

/**
 * The value arguments are used for converting a java object into the
 * language-specific expression that represents it.
 */
public class ValueArguments implements BlockArguments {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^a-z0-9_]", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_FIRSTCHAR = Pattern.compile("^[0-9].*");

    /**
     * The type we are evaluating
     */
    private Type type;

    /**
     * The scope where this type was defined
     */
    protected TypeScope scope;

    /**
     * The engine that is going to render this
     */
    private CompilerEngine engine;

    /**
     * The value to render
     */
    private Object value;

    /**
     * Value arguments constructor
     *
     * @param value The value to represent
     * @param type The type we are evaluating the value for
     * @param engine The engine that is going to render the value
     */
    public ValueArguments(Object value, Type type, TypeScope scope, CompilerEngine engine) {
        this.type = type;
        this.scope = scope;
        this.engine = engine;
        this.value = value;
    }

    /**
     * @return Return the type information
     */
    public TypeArguments getType() {
        return TypeArguments.forType(type, scope, engine);
    }

    /**
     * @return Return the raw (non-evaluated) value
     */
    public Object getRaw() {
        return value;
    }

    /**
     * @return Return the valuated expression that represents the value given
     */
    public String getExpr() {
        return engine.getValueExpression(value, type, scope);
    }

    /**
     * @return Return code-safe expression with the raw contents of this value
     */
    public String getCodesafe() {
        String name = value.toString();
        name = UNSAFE_CHARS.matcher(name).replaceAll("_");
        if (UNSAFE_FIRSTCHAR.matcher(name).matches()) name = "_" + name;
        return name;
    }

    /**
     * Some serializers might decide to render `null` in a different way, so this
     * function provides the interface for them.
     *
     * @return Return `true` if the value is null
     */
    public Boolean isNull() {
        return value == null;
    }

    /**
     * If this object is cased to string, it evaluates to the expression by default
     * @return The value expression
     */
    @Override
    public String toString() {
        return this.getExpr();
    }
}
