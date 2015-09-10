package mesosphere.marathon.interface

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
    * Indicates, if an other path identifier matches this path identifier.
    * E.g. PathId(A :: B :: Nil).matches(A :: Nil) shouldBe true
    * E.g. PathId(A :: B :: Nil).matches(A :: C :: Nil) shouldBe false
    * @param other the other path id to compare to.
    * @return true if this path id matches otherwise false.
    */
  def includes(other: PathId): Boolean
}
