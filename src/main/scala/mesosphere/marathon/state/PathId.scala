package mesosphere.marathon.state

import mesosphere.marathon.api.v2.Validation.isTrue
import mesosphere.marathon.plugin

import scala.language.implicitConversions

import com.wix.accord.dsl._
import com.wix.accord._

case class PathId(path: List[String], absolute: Boolean = true) extends Ordered[PathId] with plugin.PathId {

  def root: String = path.headOption.getOrElse("")

  def rootPath: PathId = PathId(path.headOption.map(_ :: Nil).getOrElse(Nil), absolute)

  def tail: List[String] = path.tail

  def isEmpty: Boolean = path.isEmpty

  def isRoot: Boolean = path.isEmpty

  def parent: PathId = path match {
    case Nil          => this
    case head :: Nil  => PathId(Nil, absolute)
    case head :: rest => PathId(path.reverse.tail.reverse, absolute)
  }

  def allParents: List[PathId] = if (isRoot) Nil else {
    val p = parent
    p :: p.allParents
  }

  def child: PathId = PathId(tail)

  def append(id: PathId): PathId = PathId(path ::: id.path, absolute)

  def append(id: String): PathId = append(PathId(id))

  def /(id: String): PathId = append(id)

  def restOf(parent: PathId): PathId = {
    def in(currentPath: List[String], parentPath: List[String]): List[String] = {
      if (currentPath.isEmpty) Nil
      else if (parentPath.isEmpty || currentPath.head != parentPath.head) currentPath
      else in(currentPath.tail, parentPath.tail)
    }
    PathId(in(path, parent.path), absolute)
  }

  def canonicalPath(base: PathId = PathId(Nil, absolute = true)): PathId = {
    require(base.absolute, "Base path is not absolute, canonical path can not be computed!")
    def in(remaining: List[String], result: List[String] = Nil): List[String] = remaining match {
      case head :: tail if head == "."  => in(tail, result)
      case head :: tail if head == ".." => in(tail, if (result.nonEmpty) result.tail else Nil)
      case head :: tail                 => in(tail, head :: result)
      case Nil                          => result.reverse
    }
    if (absolute) PathId(in(path)) else PathId(in(base.path ::: path))
  }

  def safePath: String = {
    require(absolute, "Path is not absolute. Can not create safe path.")
    path.mkString("_")
  }

  def toHostname: String = path.reverse.mkString(".")

  def includes(definition: plugin.PathId): Boolean = {
    //scalastyle:off return
    if (path.size < definition.path.size) return false
    path.zip(definition.path).forall { case (left, right) => left == right }
  }

  override def toString: String = toString("/")
  private def toString(delimiter: String): String = path.mkString(if (absolute) delimiter else "", delimiter, "")

  override def compare(that: PathId): Int = {
    import Ordering.Implicits._
    val seqOrder = implicitly(Ordering[List[String]])
    seqOrder.compare(canonicalPath().path, that.canonicalPath().path)
  }
}

object PathId {
  def fromSafePath(in: String): PathId = PathId(in.split("_").toList, absolute = true)
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
    * If you change this, please also change "pathType" in AppDefinition.json and
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

  private def childOf(parent: PathId): Validator[PathId] = {
    isTrue[PathId](s"Identifier is not child of $parent. Hint: use relative paths.") { child =>
      parent == PathId.empty || !parent.absolute ||
        (parent.absolute && child.canonicalPath(parent).parent == parent)
    }
  }

  /**
    * Needed for AppDefinitionValidatorTest.testSchemaLessStrictForId.
    */
  val absolutePathValidator = isTrue[PathId]("Path needs to be absolute") { path =>
    path.absolute
  }
}
