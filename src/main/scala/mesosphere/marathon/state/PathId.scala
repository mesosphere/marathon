package mesosphere.marathon
package state

import com.typesafe.scalalogging.StrictLogging
import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation.isTrue

import scala.annotation.tailrec
import scala.collection.immutable.Seq

sealed trait PathId extends Ordered[PathId] with plugin.PathId with Product {
  val absolute: Boolean

  def root: String = path.headOption.getOrElse("")

  def rootPath: PathId = PathId(path.headOption.map(_ :: Nil).getOrElse(Nil), absolute)

  def tail: Seq[String] = path.tail

  /**
    * @return True if this path is "" or "/"
    */
  def isEmpty: Boolean = path.isEmpty

  def isTopLevel: Boolean = !isRoot && parent.isRoot

  /**
    * @return True if this path is "/"
    */
  def isRoot: Boolean = path.isEmpty && (absolute == true)

  lazy val parent: PathId = path match {
    case Nil => PathId.root
    case head +: Nil => PathId.root
    case head +: rest => PathId(path.init, absolute)
  }

  def allParents: List[PathId] =
    if (isRoot) Nil
    else {
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
  def canonicalPath(base: AbsolutePathId = PathId.root): AbsolutePathId = {
    require(base.absolute, "Base path is not absolute, canonical path can not be computed!")
    @tailrec def in(remaining: Seq[String], result: Seq[String] = Nil): Seq[String] =
      remaining match {
        case head +: tail if head == "." => in(tail, result)
        case head +: tail if head == ".." => in(tail, if (result.nonEmpty) result.tail else Nil)
        case head +: tail => in(tail, head +: result)
        case Nil => result.reverse
      }
    this match {
      case rootPath: AbsolutePathId =>
        AbsolutePathId(in(path))
      case relativePath: RelativePathId =>
        AbsolutePathId(in(base.path ++ path))
    }
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

  override val toString: String = toStringWithDelimiter("/")

  protected def toStringWithDelimiter(delimiter: String): String

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

case class AbsolutePathId(path: Seq[String]) extends PathId {
  override val absolute: Boolean = true

  protected def toStringWithDelimiter(delimiter: String): String =
    path.mkString("/", delimiter, "")

  override lazy val parent: AbsolutePathId = path match {
    case Nil => PathId.root
    case head +: Nil => PathId.root
    case head +: rest => AbsolutePathId(path.init)
  }

  override def allParents: List[AbsolutePathId] =
    if (isRoot) Nil
    else {
      val p = parent
      p :: p.allParents
    }

  override def rootPath: AbsolutePathId = AbsolutePathId(path.headOption.map(_ :: Nil).getOrElse(Nil))

  override def append(id: PathId): AbsolutePathId = AbsolutePathId(path ++ id.path)

  override def append(id: String): AbsolutePathId = append(PathId(id))

  override def /(id: String): AbsolutePathId = append(id)
}

object AbsolutePathId {

  /**
    * Parse the string as a path, but interpret it as relative to root, if it is relative.
    *
    * @param path The string representation of the path to parse
    * @return An absolute path
    */
  def apply(path: String): AbsolutePathId = {
    PathId(path).canonicalPath(PathId.root)
  }
}

case class RelativePathId(path: Seq[String]) extends PathId with StrictLogging {
  override val absolute: Boolean = false

  protected def toStringWithDelimiter(delimiter: String): String =
    path.mkString(delimiter)
}

object PathId {
  def fromSafePath(in: String): AbsolutePathId = {
    if (in.isEmpty) PathId.root
    else AbsolutePathId(in.split("_").toList)
  }

  /**
    * Removes empty path segments
    * @param pieces collection with path segments
    * @param absolute is path absolute
    * @return created path
    */
  def sanitized(pieces: TraversableOnce[String], absolute: Boolean = true) =
    PathId(pieces.filter(_.nonEmpty).toList, absolute)

  def apply(pieces: Seq[String], absolute: Boolean = true): PathId = {
    if (absolute)
      AbsolutePathId(pieces)
    else
      RelativePathId(pieces)
  }

  def apply(in: String): PathId = {
    val raw = in.replaceAll("""(^/+)|(/+$)""", "").split("/")
    sanitized(raw, in.startsWith("/"))
  }

  def apply(in: String, absolute: Boolean): PathId = {
    val raw = in.replaceAll("""(^/+)|(/+$)""", "").split("/")
    sanitized(raw, absolute)
  }

  def root: AbsolutePathId = AbsolutePathId(Nil)

  def relativeEmpty: RelativePathId = RelativePathId(Nil)

  implicit class StringPathId(val stringPath: String) extends AnyVal {
    def toPath: PathId = PathId(stringPath)
    def toAbsolutePath: AbsolutePathId = PathId(stringPath).canonicalPath()
  }

  /**
    * This regular expression is used to validate each path segment of an ID.
    *
    * If you change this, please also change `PathId` in stringTypes.raml, and
    * notify the maintainers of the DCOS CLI.
    */
  private[this] val ID_PATH_SEGMENT_PATTERN =
    "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$".r

  private val validPathChars = isTrue[PathId](s"must fully match regular expression '${ID_PATH_SEGMENT_PATTERN.pattern.pattern()}'") { id =>
    id.path.forall(part => ID_PATH_SEGMENT_PATTERN.pattern.matcher(part).matches())
  }

  private val reservedKeywords = Seq("restart", "tasks", "versions", ".", "..")

  private val withoutReservedKeywords =
    isTrue[PathId](s"must not end with any of the following reserved keywords: ${reservedKeywords.mkString(", ")}") { id =>
      id.path.lastOption.forall(last => !reservedKeywords.contains(id.path.last))
    }

  /**
    * For external usage. Needed to overwrite the whole description, e.g. id.path -> id.
    */
  implicit val pathIdValidator = validator[PathId] { path =>
    path is validPathChars
    path is withoutReservedKeywords
    path is childOf(path.parent)
  }

  /**
    * Validate path with regards to some parent path.
    * @param base Path of parent.
    */
  def validPathWithBase(base: PathId): Validator[PathId] =
    validator[PathId] { path =>
      path is validPathChars
      path is childOf(base)
    }

  /**
    * Make sure that the given path is a child of the defined parent path.
    * Every relative path can be ignored.
    */
  private def childOf(parent: PathId): Validator[PathId] = {
    isTrue[PathId](s"Identifier is not child of '$parent'") { child =>
      parent match {
        case _: RelativePathId => true
        case p: AbsolutePathId => child.canonicalPath(p).parent == parent
      }
    }
  }

  /**
    * Makes sure, the path is not only the root path and is not empty.
    */
  val nonEmptyPath = isTrue[PathId]("Path must contain at least one path element") { _.path.nonEmpty }

  /**
    * Needed for AppDefinitionValidatorTest.testSchemaLessStrictForId.
    */
  val absolutePathValidator = isTrue[PathId]("Path needs to be absolute") { _.absolute }

  val topLevel = isTrue[PathId]("Path needs to be top-level") { _.isTopLevel }
}
