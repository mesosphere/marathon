package mesosphere.marathon.state

import scala.language.implicitConversions

case class GroupId(path: List[String]) {

  def root: String = path.head

  def tail: List[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def safePath: String = path.mkString("_")

  def parent: GroupId = if (tail.isEmpty) this else GroupId(path.reverse.tail.reverse)

  override def toString: String = path.mkString("/")
}

object GroupId {
  implicit def string2GroupId(in: String): GroupId = GroupId(in.replaceAll("""(^/+)|(/+$)""", "").split("/").toList)
  implicit def groupId2String(in: GroupId): String = in.toString
}

