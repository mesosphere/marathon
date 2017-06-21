package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation.isTrue
import mesosphere.marathon.plugin

import scala.annotation.tailrec
import scala.collection.immutable.Seq

case class PathId(path: Seq[String], absolute: Boolean = true) extends Ordered[PathId] with plugin.PathId {

  def root: String = path.headOption.getOrElse("")

  def rootPath: PathId = PathId(path.headOption.map(_ :: Nil).getOrElse(Nil), absolute)

  def tail: Seq[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.isEmpty

  def parent: PathId = path match {
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
    require(absolute, "Path is not absolute. Can not create safe path.")
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
  def apply(in: String): PathId =
    PathId(in.replaceAll("""(^/+)|(/+$)""", "").split("/").filter(_.nonEmpty).toList, in.startsWith("/"))
  def empty: PathId = PathId(Nil)

  implicit class StringPathId(val stringPath: String) extends AnyVal {
    def toPath: PathId = PathId(stringPath)
    def toRootPath: PathId = PathId(stringPath).canonicalPath()
  }

  /**
    * This regular expression is used to validate each path segment of an ID.
    *
    * If you change this, please also change `pathType` in AppDefinition.json, `PathId` in stringTypes.raml, and
    * notify the maintainers of the DCOS CLI.
    */
  private[this] val ID_PATH_SEGMENT_PATTERN =
    "^(([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])\\.)*([a-z0-9]|[a-z0-9][a-z0-9\\-]*[a-z0-9])|(\\.|\\.\\.)$".r

  private val validPathChars = new Validator[PathId] {
    override def apply(pathId: PathId): Result = {
      validate(pathId.path)(validator = pathId.path.each should matchRegexFully(ID_PATH_SEGMENT_PATTERN.pattern))
    }
  }

  /**
    * For external usage. Needed to overwrite the whole description, e.g. id.path -> id.
    */
  implicit val pathIdValidator = validator[PathId] { path =>
    path is childOf(path.parent)
    path is validPathChars
  }

  /**
    * Validate path with regards to some parent path.
    * @param base Path of parent.
    */
  def validPathWithBase(base: PathId): Validator[PathId] = validator[PathId] { path =>
    path is childOf(base)
    path is validPathChars
  }

  /**
    * Make sure that the given path is a child of the defined parent path.
    * Every relative path can be ignored.
    */
  private def childOf(parent: PathId): Validator[PathId] = {
    isTrue[PathId](s"Identifier is not child of $parent. Hint: use relative paths.") { child =>
      !parent.absolute || (child.canonicalPath(parent).parent == parent)
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
}
