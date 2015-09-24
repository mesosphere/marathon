package mesosphere.marathon.plugin

/**
  * A path based identifier.
  */
trait PathId {

  /**
    * The path of this id.
    * e.g.: rootGroup :: subGroup :: subSubGroup :: appId :: Nil
    * @return the list of path elements
    */
  def path: List[String]

  /**
    * String representation of that path identifier
    * @return the string representation of that path
    */
  def toString: String
}
