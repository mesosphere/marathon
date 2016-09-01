package mesosphere.marathon.api.v2.validation

import com.wix.accord.{ Result, Validator }
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ Network, PodDef }
import mesosphere.marathon.state.PathId

import scala.collection.immutable.Seq

/**
  * Defines implicit validation for PodDef
  */
object PodsValidation {

  import Validation._

  val podDefValidator: Validator[PodDef] = validator[PodDef] { pod =>
    pod.id is valid(idValidator)
    pod.networks is valid(networksValidator)
    pod.networks is every(networkValidator)
    // user -- no validation required, passed through to Mesos
    // TODO(jdef) volumes
    // TODO(jdef) environment (support pluggable validation for these?)
    // TODO(jdef) labels (support pluggable validation for these?)
  }

  val idValidator = new Validator[String] {
    override def apply(path: String): Result = {
      val validator = implicitly[Validator[PathId]]
      val pathId = PathId(path)
      validator(pathId) and PathId.absolutePathValidator(pathId)
    }
  }

  val networkValidator: Validator[Network] = validator[Network] { network =>
    network.name.each is valid(validName)
  }

  val networksValidator: Validator[Seq[Network]] = isTrue[Seq[Network]]("Duplicate networks are not allowed") { nets =>
    val unnamedAtMostOnce = nets.filter(_.name.isEmpty).size < 2
    val realNamesAtMostOnce: Boolean = !nets.flatMap(_.name).groupBy(name => name).exists(_._2.size > 1)
    unnamedAtMostOnce && realNamesAtMostOnce
  }

  val namePattern = """^[a-z0-9]([-a-z0-9]*[a-z0-9])?$""".r
  val validName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      namePattern,
      "must contain only alphanumeric chars or hyphens, and must begin with a letter")
    name.length should be > 0
    name.length should be < 64
  }
}
