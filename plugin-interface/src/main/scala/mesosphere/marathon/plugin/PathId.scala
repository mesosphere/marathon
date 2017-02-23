package mesosphere.marathon
package plugin

import scala.collection.immutable.Seq

/**
  * A path based identifier.
  */
trait PathId {

  /**
    * The path of this id.
    * e.g.: rootGroup :: subGroup :: subSubGroup :: appId :: Nil
    * @return the list of path elements
    */
  def path: Seq[String]

  /**
    * String representation of that path identifier
    * @return the string representation of that path
    */
  def toString: String
}
