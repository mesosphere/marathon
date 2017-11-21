package io.mesosphere.types.dtr.compiler.internal.arguments;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.models.internal.Type;
import io.mesosphere.types.dtr.models.internal.TypeScope;

/**
 * A `ValueArguments` instance extension that contains a pre-calculated value
 * and does not require to query the engine for fetching it.
 */
public class PrecalcValueArguments extends ValueArguments {

    private String varExpression;
    private Boolean varNull;

    /**
     * Create a ValueArguments expression with a pre-composed value expression
     * @param value The value to encapsulate
     * @param type The type of the value
     * @param isNull True if the contents we carry are null
     * @param expression The expression to return
     */
    public PrecalcValueArguments(Object value, Type type, TypeScope scope, Boolean isNull, String expression) {
        super(value, type, scope,null);
        this.varExpression = expression;
        this.varNull = isNull;
    }

    @Override
    public String getExpr() {
        return varExpression;
    }

    @Override
    public Boolean isNull() {
        return varNull;
    }
}
