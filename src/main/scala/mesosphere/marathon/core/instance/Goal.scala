package mesosphere.marathon
package core.instance

/**
  * Defines goal of the instance.
  * Goal is set by an orchestration layer and interpreted by scheduler layer.
  * In the end it is used by low-level scheduler to make scheduling decisions e.g. should the task associated with this instance be launched or killed?
  */
sealed trait Goal

object Goal {

  /**
    * There should always be a running Mesos task associated by instance in this state.
    * Instance with Suspended Goal might be changed to both [[Running]] or [[Suspended]] by orchestration layer.
    */
  object Running extends Goal

  /**
    * Tasks associated with this instance shall be killed, but the instance needs to be kept in the state.
    * This is typically needed for resident instances, where we need to persist the reservation for re-launch.
    * Instance with Suspended Goal might be changed to both [[Running]] or [[Decommissioned]].
    */
  object Suspended extends Goal

  /**
    * All tasks associated with this instance shall be killed, and after they're reportedly terminal, the instance shall be removed because it's no longer needed.
    * This is typically used for ephemeral instances, when scaling down, deleting a service or upgrading.
    * This is terminal Goal, instance with this goal won't transition into any other Goal from now on.
    */
  object Decommissioned extends Goal

}
