package mesosphere.marathon.state

import scala.language.implicitConversions

case class PathId(path: List[String], absolute: Boolean = true) {

  def root: String = path.headOption.getOrElse("")

  def tail: List[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.size == 1

  def safePath: String = path.mkString("_")

  def parent: PathId = if (tail.isEmpty) this else PathId(path.reverse.tail.reverse)

  def child: PathId = PathId(tail)

  def append(id: PathId): PathId = PathId(path ::: id.path)

  def restOf(parent: PathId): PathId = {
    def in(currentPath: List[String], parentPath: List[String]): List[String] = {
      if (currentPath.isEmpty) Nil
      else if (parentPath.isEmpty || currentPath.head != parentPath.head) currentPath
      else in(currentPath.tail, parentPath.tail)
    }
    PathId(in(path, parent.path))
  }

  def canonicalPath(base: PathId = PathId(Nil, absolute = true)): PathId = {
    require(base.absolute, "Base path is not absolute, canonical path can not be computed!")
    def in(remaining: List[String], result: List[String] = Nil): List[String] = remaining match {
      case head :: tail if head == "."  => in(tail, result)
      case head :: tail if head == ".." => in(tail, result.tail)
      case head :: tail                 => in(tail, head :: result)
      case Nil                          => result.reverse
    }
    if (absolute) PathId(in(path)) else PathId(in(base.path ::: path))
  }

  override def toString: String = path.mkString(if (absolute) "/" else "", "/", "")
}

object PathId {
  implicit def apply(in: String): PathId = PathId(in.replaceAll("""(^/+)|(/+$)""", "").split("/").filter(_.nonEmpty).toList, in.startsWith("/"))
  implicit def pathId2String(in: PathId): String = in.toString
  def empty: PathId = PathId(Nil)
}

