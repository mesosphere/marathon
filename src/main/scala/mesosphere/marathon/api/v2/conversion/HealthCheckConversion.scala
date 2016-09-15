package mesosphere.marathon.api.v2.conversion

import mesosphere.marathon.core.health.HealthCheck
import mesosphere.marathon.raml.{ HealthCheck => RAMLHealthCheck }

trait HealthCheckConversion {
  implicit val fromHealthCheckToAPIObject = Converter { apiObject: RAMLHealthCheck => Option.empty[HealthCheck] }
  implicit val fromAPIObjectToHealthCheck = Converter { model: HealthCheck => Option.empty[RAMLHealthCheck] }
}
