package mesosphere.marathon
package api

/**
  * Simple mix-in trait which identifies an instance as a JAX-RS controller
  *
  * Only used so our controllers can be collectively identified as something more specific that `Object`
  */
trait JaxResource {}
