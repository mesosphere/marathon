package mesosphere.marathon.state

import scala.language.implicitConversions

case class GroupId(path: List[String]) {

  def root: String = path.headOption.getOrElse("")

  def tail: List[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.size == 1

  def safePath: String = path.mkString("_")

  def parent: GroupId = if (tail.isEmpty) this else GroupId(path.reverse.tail.reverse)

  def child: GroupId = GroupId(tail)

  def append(name: String): GroupId = GroupId(path ::: List(name))

  def restOf(parent: GroupId): GroupId = {
    def in(currentPath: List[String], parentPath: List[String]): List[String] = {
      if (currentPath.isEmpty) Nil
      else if (parentPath.isEmpty || currentPath.head != parentPath.head) currentPath
      else in(currentPath.tail, parentPath.tail)
    }
    GroupId(in(path, parent.path))
  }

  override def toString: String = path.mkString("/")
}

object GroupId {
  implicit def apply(in: String): GroupId = GroupId(in.replaceAll("""(^/+)|(/+$)""", "").split("/").toList)
  implicit def groupId2String(in: GroupId): String = in.toString
  def empty: GroupId = GroupId(Nil)
}

