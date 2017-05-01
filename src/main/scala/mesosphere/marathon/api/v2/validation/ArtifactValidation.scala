package mesosphere.marathon
package api.v2.validation

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.raml.Artifact

trait ArtifactValidation {
  implicit val artifactUriIsValid: Validator[Artifact] = validator[Artifact] { artifact =>
    artifact.uri is valid(api.v2.Validation.uriIsValid)
  }
}

object ArtifactValidation extends ArtifactValidation
