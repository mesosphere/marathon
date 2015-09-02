package mesosphere.marathon.health

import javax.validation.Validation

import mesosphere.jackson.CaseClassModule
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.state.Command
import mesosphere.marathon.{ MarathonSpec, Protos }
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class HealthCheckTest extends MarathonSpec {

  test("ToProto") {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = 0,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      maxConsecutiveFailures = 0
    )

    val proto = healthCheck.toProto

    assert("/health" == proto.getPath)
    assert(Protocol.HTTP == proto.getProtocol)
    assert(0 == proto.getPortIndex)
    assert(10 == proto.getGracePeriodSeconds)
    assert(60 == proto.getIntervalSeconds)
    assert(0 == proto.getMaxConsecutiveFailures)
    assert(!proto.hasOverridePort)
  }

  test("ToProtoOverridePort") {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = 0,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      maxConsecutiveFailures = 0,
      overridePort = Some(12345)
    )

    val proto = healthCheck.toProto

    assert("/health" == proto.getPath)
    assert(Protocol.HTTP == proto.getProtocol)
    assert(0 == proto.getPortIndex)
    assert(10 == proto.getGracePeriodSeconds)
    assert(60 == proto.getIntervalSeconds)
    assert(0 == proto.getMaxConsecutiveFailures)
    assert(12345 == proto.getOverridePort)
  }

  test("ToProtoTcp") {
    val healthCheck = HealthCheck(
      protocol = Protocol.TCP,
      portIndex = 1,
      gracePeriod = 7.seconds,
      interval = 35.seconds,
      maxConsecutiveFailures = 10
    )

    val proto = healthCheck.toProto

    assert(Protocol.TCP == proto.getProtocol)
    assert(1 == proto.getPortIndex)
    assert(7 == proto.getGracePeriodSeconds)
    assert(35 == proto.getIntervalSeconds)
    assert(10 == proto.getMaxConsecutiveFailures)
  }

  test("MergeFromProto") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setPortIndex(0)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .setOverridePort(12345)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = 0,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      overridePort = Some(12345)
    )

    assert(mergeResult == expectedResult)
  }

  test("MergeFromProtoTcp") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(Protocol.TCP)
      .setPortIndex(1)
      .setGracePeriodSeconds(7)
      .setIntervalSeconds(35)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = None,
      protocol = Protocol.TCP,
      portIndex = 1,
      gracePeriod = 7.seconds,
      interval = 35.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10
    )

    assert(mergeResult == expectedResult)
  }

  test("MergeFromProtoHttps") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTPS)
      .setPortIndex(0)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTPS,
      portIndex = 0,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10
    )

    assert(mergeResult == expectedResult)
  }

  private[this] def toJson(healthCheck: HealthCheck): String = {
    import mesosphere.marathon.api.v2.json.Formats._
    Json.prettyPrint(Json.toJson(healthCheck))
  }
  private[this] def fromJson(json: String): HealthCheck = {
    import mesosphere.marathon.api.v2.json.Formats._
    Json.fromJson[HealthCheck](Json.parse(json)).get
  }

  test("SerializationRoundtrip empty") {
    val original = HealthCheck()
    val json = toJson(original)
    val readResult = fromJson(json)
    assert(readResult == original)
  }

  test("Read COMMAND health check") {
    val json =
      """
        {
          "protocol": "COMMAND",
          "portIndex": 0,
          "command": { "value": "echo healthy" },
          "gracePeriodSeconds": 300,
          "intervalSeconds": 60,
          "timeoutSeconds": 20,
          "maxConsecutiveFailures": 3
        }
      """
    val expected =
      HealthCheck(
        protocol = Protocol.COMMAND,
        command = Some(Command("echo healthy"))
      )
    val readResult = fromJson(json)
    assert(readResult == expected)
  }

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(hc: HealthCheck, template: String) = {
      val violations = validator.validate(hc).asScala
      assert(violations.exists(_.getMessageTemplate == template))
    }

    def shouldNotViolate(hc: HealthCheck, template: String) = {
      val violations = validator.validate(hc).asScala
      assert(!violations.exists(_.getMessageTemplate == template))
    }

    shouldNotViolate(HealthCheck(), "")

    shouldViolate(
      HealthCheck(protocol = Protocol.COMMAND, path = Some("/health")),
      "Health check protocol must match supplied fields."
    )

    shouldViolate(
      HealthCheck(protocol = Protocol.COMMAND, command = None),
      "Health check protocol must match supplied fields."
    )

    shouldViolate(
      HealthCheck(
        protocol = Protocol.HTTP,
        command = Some(Command("echo healthy"))
      ),
      "Health check protocol must match supplied fields."
    )

    shouldViolate(
      HealthCheck(
        protocol = Protocol.TCP,
        path = Some("/")
      ),
      "Health check protocol must match supplied fields."
    )

    shouldViolate(
      HealthCheck(
        protocol = Protocol.TCP,
        command = Some(Command("echo healthy"))
      ),
      "Health check protocol must match supplied fields."
    )

  }

}
