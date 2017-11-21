package io.mesosphere.types.dtr.compiler.internal;

import java.util.Map;

/**
 * A set of parameters that are passed down to a code block,
 * defining the template fields and scope.
 */
public interface BlockArguments {

    /**
     * This interface is currently blank since the StringTemplate engine
     * can access the properties of the instance directly or through getters.
     *
     * However if somebody decides to implement another template engine or
     * another type of compiler you could define something like this:
     *
     * //
     * // Return the arguments as a map
     * //
     * Map&lt;String, Object&gt; toMap();
     *
     */

}
