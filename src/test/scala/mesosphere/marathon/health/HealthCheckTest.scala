package mesosphere.marathon.health

import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.state.Command
import mesosphere.marathon.{ MarathonTestHelper, MarathonSpec, Protos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import com.wix.accord.validate

class HealthCheckTest extends MarathonSpec {

  test("ToProto") {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(0),
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
    assert(!proto.hasPort)
  }

  test("ToProto with port") {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      maxConsecutiveFailures = 0,
      port = Some(12345)
    )

    val proto = healthCheck.toProto

    assert("/health" == proto.getPath)
    assert(Protocol.HTTP == proto.getProtocol)
    assert(!proto.hasPortIndex)
    assert(10 == proto.getGracePeriodSeconds)
    assert(60 == proto.getIntervalSeconds)
    assert(0 == proto.getMaxConsecutiveFailures)
    assert(12345 == proto.getPort)
  }

  test("ToProtoTcp") {
    val healthCheck = HealthCheck(
      protocol = Protocol.TCP,
      portIndex = Some(1),
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

  test("MergeFromProto with portIndex") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setPortIndex(0)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(0),
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      port = None
    )

    assert(mergeResult == expectedResult)
  }

  test("MergeFromProto with port") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .setPort(12345)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = None,
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      port = Some(12345)
    )

    assert(mergeResult == expectedResult)
  }

  test("MergeFromProto with neither port nor portIndex") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(0),
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      port = None
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
      portIndex = Some(1),
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
      portIndex = Some(0),
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

  test("Read COMMAND health check (portIndex may be provided for backwards-compatibility)") {
    val json =
      """
        {
          "protocol": "COMMAND",
          "command": { "value": "echo healthy" },
          "portIndex": 0,
          "gracePeriodSeconds": 300,
          "intervalSeconds": 60,
          "timeoutSeconds": 20,
          "maxConsecutiveFailures": 3
        }
      """
    val expected =
      HealthCheck(
        protocol = Protocol.COMMAND,
        command = Some(Command("echo healthy")),
        portIndex = Some(0)
      )
    val readResult = fromJson(json)
    assert(readResult == expected)
  }

  def shouldBeInvalid(hc: HealthCheck): Unit = {
    assert(validate(hc).isFailure)
  }

  def shouldBeValid(hc: HealthCheck): Unit = {
    val result = validate(hc)
    assert(result.isSuccess, s"violations: ${ValidationHelper.getAllRuleConstrains(result)}")
  }

  test("A default HealthCheck should be valid") {
    // portIndex is added in the Format conversion of the app
    shouldBeValid(HealthCheck(portIndex = Some(0)))
  }

  test("path is not accepted for a COMMAND HealthCheck") {
    shouldBeInvalid(HealthCheck(protocol = Protocol.COMMAND, path = Some("/health")))
  }

  test("command is required for a COMMAND HealthCheck") {
    shouldBeInvalid(HealthCheck(protocol = Protocol.COMMAND, command = None))
  }

  test("command is not accepted for a HTTP HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.HTTP,
      command = Some(Command("echo healthy"))
    ))
  }

  test("path is not accepted for a TCP HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.TCP,
      path = Some("/")
    ))
  }

  test("command is not accepted for a TCP HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.TCP,
      command = Some(Command("echo healthy"))
    ))
  }

  test("port is not accepted for a COMMAND HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.COMMAND,
      port = Some(1)
    ))
  }

  test("portIndex is not accepted for a COMMAND HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.COMMAND,
      portIndex = Some(0)
    ))
  }

  test("both port and portIndex are not accepted at the same time for a HTTP HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1),
      portIndex = Some(0)
    ))
  }

  test("both port and portIndex are not accepted at the same time for a TCP HealthCheck") {
    shouldBeInvalid(HealthCheck(
      protocol = Protocol.TCP,
      port = Some(1),
      portIndex = Some(0)
    ))
  }

  test("port is accepted for a HTTP HealthCheck") {
    shouldBeValid(HealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1)
    ))
  }

  test("port is accepted for a TCP HealthCheck") {
    shouldBeValid(HealthCheck(
      protocol = Protocol.TCP,
      port = Some(1)
    ))
  }

  test("portIndex is accepted for a HTTP HealthCheck") {
    shouldBeValid(HealthCheck(
      protocol = Protocol.HTTP,
      portIndex = Some(0)
    ))
  }

  test("portIndex is accepted for a TCP HealthCheck") {
    shouldBeValid(HealthCheck(
      protocol = Protocol.TCP,
      portIndex = Some(0)
    ))
  }

  test("getPort") {
    import MarathonTestHelper.Implicits._
    val check = new HealthCheck(port = Some(1234))
    val task = MarathonTestHelper.runningTask("test_id").withHostPorts(Seq(4321))

    assert(check.hostPort(task.launched.get).contains(1234))
  }
}
