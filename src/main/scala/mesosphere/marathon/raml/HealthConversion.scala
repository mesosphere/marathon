package mesosphere.marathon
package raml

trait HealthConversion extends DefaultConversions {

  implicit val healthRamlWriter: Writes[core.health.Health, Health] = Writes { health =>
    Health(
      alive = health.alive,
      consecutiveFailures = health.consecutiveFailures,
      firstSuccess = health.firstSuccess.map(_.toString),
      instanceId = health.instanceId.toRaml,
      lastSuccess = health.lastSuccess.map(_.toString),
      lastFailure = health.lastFailure.map(_.toString),
      lastFailureCause = health.lastFailureCause
    )
  }
}
