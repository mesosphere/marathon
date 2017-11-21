package io.mesosphere.types.dtr.compiler;

import io.mesosphere.types.dtr.compiler.internal.CompilerEngine;
import io.mesosphere.types.dtr.repository.internal.RepositoryView;

/**
 * Type compiler generates the sources for the given types
 */
public class TypeCompiler {

    /**
     * The engine to use for compiling the type
     */
    private CompilerEngine engine;

    /**
     * The repository view from which to load the compiler templates
     */
    private RepositoryView compilerView;

    /**
     * Initialize the type compiler view
     * @param engine
     * @param compilerView
     */
    public TypeCompiler(CompilerEngine engine, RepositoryView compilerView) {
        this.engine = engine;
        this.compilerView = compilerView;
    }
}
