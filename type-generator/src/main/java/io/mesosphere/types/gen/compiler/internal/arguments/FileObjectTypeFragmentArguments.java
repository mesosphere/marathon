package io.mesosphere.types.gen.compiler.internal.arguments;

import io.mesosphere.types.gen.compiler.internal.BlockArguments;
import io.mesosphere.types.gen.compiler.internal.CompilerEngine;
import io.mesosphere.types.gen.models.internal.Type;
import io.mesosphere.types.gen.models.internal.TypeScope;

public class FileObjectTypeFragmentArguments implements BlockArguments {

    /**
     * The scope where the type was defined
     */
    private TypeScope scope;

    /**
     * The type we are interfacing
     */
    protected Type type;

    /**
     * The engine that is going to compile the type
     */
    protected CompilerEngine engine;

    public FileObjectTypeFragmentArguments(Type type, TypeScope scope, CompilerEngine engine) {
        this.type = type;
        this.scope = scope;
        this.engine = engine;
    }

    public TypeArguments getType() {
        return TypeArguments.forType(type, scope, engine);
    }
}
