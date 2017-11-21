package io.mesosphere.types.dtr.compiler.internal;

/**
 * Various type-specific fragments that should exist in all implementations
 * of the compilers.
 */
public enum TypeFragment {

    /**
     * Type declaration statement.
     * Arguments: type
     *
     * For example:
     *
     * class X {
     *     ...
     * }
     */
    DECLARATION_STMT,

    /**
     * Type constructor statement.
     * Arguments: type
     *
     * For example:
     *
     * X(Type arg) {
     *     this.arg = arg;
     * }
     */
    CONSTRUCTOR_STMT,

    /**
     * Type signature expression.
     * Arguments: type
     *
     * For example:
     *
     * X
     */
    SIGNATURE_EXPR,

    /**
     * Type instantiation expression.
     * Arguments: type, propValues
     *
     * For example:
     *
     * new X(propA, propB)
     */
    INSTANTIATE_EXPR,

    /**
     * Property access expression.
     * Arguments: type, propKey
     *
     * For example:
     *
     * X.prop
     */
    ACCESS_EXPR

}
