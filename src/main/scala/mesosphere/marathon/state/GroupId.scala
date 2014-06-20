package mesosphere.marathon.state

import scala.language.implicitConversions

case class GroupId(path: List[String], absolute: Boolean = true) {

  def root: String = path.headOption.getOrElse("")

  def tail: List[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.size == 1

  def safePath: String = path.mkString("_")

  def parent: GroupId = if (tail.isEmpty) this else GroupId(path.reverse.tail.reverse)

  def child: GroupId = GroupId(tail)

  def append(id: GroupId): GroupId = GroupId(path ::: id.path)

  def restOf(parent: GroupId): GroupId = {
    def in(currentPath: List[String], parentPath: List[String]): List[String] = {
      if (currentPath.isEmpty) Nil
      else if (parentPath.isEmpty || currentPath.head != parentPath.head) currentPath
      else in(currentPath.tail, parentPath.tail)
    }
    GroupId(in(path, parent.path))
  }

  def canonicalPath(base: GroupId = GroupId(Nil, absolute = true)): GroupId = {
    require(base.absolute, "Base path is not absolute, canonical path can not be computed!")
    def in(remaining: List[String], result: List[String] = Nil): List[String] = remaining match {
      case head :: tail if head == "."  => in(tail, result)
      case head :: tail if head == ".." => in(tail, result.tail)
      case head :: tail                 => in(tail, head :: result)
      case Nil                          => result.reverse
    }
    if (absolute) GroupId(in(path)) else GroupId(in(base.path ::: path))
  }

  override def toString: String = path.mkString(if (absolute) "/" else "", "/", "")
}

object GroupId {
  implicit def apply(in: String): GroupId = GroupId(in.replaceAll("""(^/+)|(/+$)""", "").split("/").toList, in.startsWith("/"))
  implicit def groupId2String(in: GroupId): String = in.toString
  def empty: GroupId = GroupId(Nil)
}

