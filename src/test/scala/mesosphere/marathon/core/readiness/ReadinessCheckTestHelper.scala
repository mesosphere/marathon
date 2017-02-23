package mesosphere.marathon
package core.readiness

import scala.concurrent.duration._

object ReadinessCheckTestHelper {
  val defaultHttp = ReadinessCheck()

  val alternativeHttps = ReadinessCheck(
    name = "dcosMigrationApi",
    protocol = ReadinessCheck.Protocol.HTTPS,
    path = "/v1/plan",
    portName = "dcos-migration-api",
    interval = 10.seconds,
    timeout = 2.seconds,
    httpStatusCodesForReady = Set(201),
    preserveLastResponse = true
  )
}
