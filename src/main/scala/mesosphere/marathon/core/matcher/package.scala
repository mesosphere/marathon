package mesosphere.marathon.core

/**
  * The matcher package deals with matching resource offers from Mesos
  * with tasks which should launched on top of these.
  *
  * This package does not offer concrete OfferMatches but provides
  * interfaces for more specific offer matches (e.g. AppOfferMatchers).
  */
package object matcher
