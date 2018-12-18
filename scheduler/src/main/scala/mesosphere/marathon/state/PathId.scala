package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.plugin

import scala.annotation.tailrec
import scala.collection.immutable.Seq

case class PathId(path: Seq[String], absolute: Boolean = true) extends Ordered[PathId] with plugin.PathId {

  def root: String = path.headOption.getOrElse("")

  def rootPath: PathId = PathId(path.headOption.map(_ :: Nil).getOrElse(Nil), absolute)

  def tail: Seq[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.isEmpty

  lazy val parent: PathId = path match {
    case Nil => this
    case head +: Nil => PathId(Nil, absolute)
    case head +: rest => PathId(path.init, absolute)
  }

  def allParents: List[PathId] = if (isRoot) Nil else {
    val p = parent
    p :: p.allParents
  }

  def child: PathId = PathId(tail)

  def append(id: PathId): PathId = PathId(path ++ id.path, absolute)

  def append(id: String): PathId = append(PathId(id))

  def /(id: String): PathId = append(id)

  def restOf(parent: PathId): PathId = {
    @tailrec def in(currentPath: Seq[String], parentPath: Seq[String]): Seq[String] = {
      if (currentPath.isEmpty) Nil
      else if (parentPath.isEmpty || currentPath.headOption != parentPath.headOption) currentPath
      else in(currentPath.tail, parentPath.tail)
    }
    PathId(in(path, parent.path), absolute)
  }

  /*
   * Given some base path, convert the provided path to an absolute path, resolving .. and . references
   *
   * PathId("child").canonicalPath(PathId("/parent")) == PathId("/parent/child")
   */
  def canonicalPath(base: PathId = PathId(Nil, absolute = true)): PathId = {
    require(base.absolute, "Base path is not absolute, canonical path can not be computed!")
    @tailrec def in(remaining: Seq[String], result: Seq[String] = Nil): Seq[String] = remaining match {
      case head +: tail if head == "." => in(tail, result)
      case head +: tail if head == ".." => in(tail, if (result.nonEmpty) result.tail else Nil)
      case head +: tail => in(tail, head +: result)
      case Nil => result.reverse
    }
    if (absolute) PathId(in(path)) else PathId(in(base.path ++ path))
  }

  def safePath: String = {
    require(absolute, s"Path absolute flag is not true for path ${this.toString}. Can not create safe path.")
    path.mkString("_")
  }

  def toHostname: String = path.reverse.mkString(".")

  def includes(definition: plugin.PathId): Boolean = {
    if (path.size < definition.path.size) return false
    path.zip(definition.path).forall { case (left, right) => left == right }
  }

  override val toString: String = toString("/")

  private def toString(delimiter: String): String = path.mkString(if (absolute) delimiter else "", delimiter, "")

  override def compare(that: PathId): Int = {
    import Ordering.Implicits._
    val seqOrder = implicitly(Ordering[Seq[String]])
    seqOrder.compare(canonicalPath().path, that.canonicalPath().path)
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case that: PathId => (that eq this) || (that.hashCode == hashCode && that.absolute == absolute && that.path == path)
      case _ => false
    }
  }

  override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)
}

object PathId {
  def fromSafePath(in: String): PathId = {
    if (in.isEmpty) PathId.empty
    else PathId(in.split("_").toList, absolute = true)
  }

  /**
    * Removes empty path segments
    * @param pieces collection with path segments
    * @param absolute is path absolute
    * @return created path
    */
  def sanitized(pieces: TraversableOnce[String], absolute: Boolean = true) =
    PathId(pieces.filter(_.nonEmpty).toList, absolute)

  def apply(in: String): PathId = {
    val raw = in.replaceAll("""(^/+)|(/+$)""", "").split("/")
    sanitized(raw, in.startsWith("/"))
  }

  def empty: PathId = PathId(Nil)

  implicit class StringPathId(val stringPath: String) extends AnyVal {
    def toPath: PathId = PathId(stringPath)
    def toRootPath: PathId = PathId(stringPath).canonicalPath()
  }
}
