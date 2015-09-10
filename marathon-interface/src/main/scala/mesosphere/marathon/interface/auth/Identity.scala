package mesosphere.marathon.interface.auth

/**
  * Base trait that represents an identity.
  */
trait Identity {

  /**
    * The string identifier of that identity.
    * @return the unique identifier of that user.
    */
  def id: String
}

