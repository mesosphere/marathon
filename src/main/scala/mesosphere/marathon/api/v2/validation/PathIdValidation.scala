package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import mesosphere.marathon.api.v2.Validation.isTrue
import mesosphere.marathon.state.PathId

trait PathIdValidation {

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

  private val reservedKeywords = Seq("restart", "tasks", "versions")

  private val withoutReservedKeywords = isTrue[PathId](s"must not end with any of the following reserved keywords: ${reservedKeywords.mkString(", ")}") { id =>
    id.path.lastOption.forall(last => !reservedKeywords.contains(id.path.last))
  }

  /**
    * For external usage. Needed to overwrite the whole description, e.g. id.path -> id.
    */
  implicit val pathIdValidator = validator[PathId] { path =>
    path is childOf(path.parent)
    path is validPathChars
    path is withoutReservedKeywords
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
