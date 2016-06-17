package mesosphere.marathon.plugin.auth

/**
  * All resources that can be protected via ViewResource or ChangeResource Action.
  */
sealed trait AuthorizedResource

case object AuthorizedResource {

  /**
    * The leader resource to view change the current leader.
    */
  case object Leader extends AuthorizedResource

  /**
    * The events resource to subscribe/unsubscribe/attach to the event stream.
    */
  case object Events extends AuthorizedResource

  /**
    * The system configuration (e.g. info).
    */
  case object SystemConfig extends AuthorizedResource

}
