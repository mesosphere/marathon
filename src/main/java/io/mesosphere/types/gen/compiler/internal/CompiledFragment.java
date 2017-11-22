package io.mesosphere.types.gen.compiler.internal;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A compiled fragment is instantiated from the compiler engine and can be
 * evaluated at any time.
 */
public interface CompiledFragment {

    /**
     * Write the compiled fragment to the given output stream, using the
     * arguments provided for the template placeholders.
     *
     * @param os The output stream
     * @param args The arguments to the template
     */
    void write(OutputStream os, BlockArguments args) throws IOException;

    /**
     * Collect the output of the compiled fragment and return it as a string,
     * using the arguments provided for the template placeholders.
     *
     * @param args The arguments to the template
     */
    String render(BlockArguments args) throws IOException;

}
