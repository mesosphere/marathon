package mesosphere.marathon.core.health

import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.state.{ Command, PortDefinition }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper, Protos }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import com.wix.accord.validate

class HealthCheckTest extends MarathonSpec {

  test("ToProto") {
    val healthCheck = HttpHealthCheck(
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
    val healthCheck = HttpHealthCheck(
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
    val healthCheck = TcpHealthCheck(
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

    val mergeResult = HealthCheck.mergeFromProto(proto)

    val expectedResult = HttpHealthCheck(
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

    val mergeResult = HealthCheck.mergeFromProto(proto)

    val expectedResult = HttpHealthCheck(
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

    val mergeResult = HealthCheck.mergeFromProto(proto)

    val expectedResult = HttpHealthCheck(
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

    val mergeResult = HealthCheck.mergeFromProto(proto)

    val expectedResult = TcpHealthCheck(
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

    val mergeResult = HealthCheck.mergeFromProto(proto)

    val expectedResult = HttpHealthCheck(
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
    Json.fromJson[HealthCheck](Json.parse(json))(HealthCheckFormat).get
  }

  test("SerializationRoundtrip empty") {
    val original = CommandHealthCheck(command = Command("true"))
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
    val expected = CommandHealthCheck(command = Command("echo healthy"))
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
    val expected = CommandHealthCheck(command = Command("echo healthy"))
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
    shouldBeValid(HttpHealthCheck(portIndex = Some(0)))
  }

  test("both port and portIndex are not accepted at the same time for a HTTP HealthCheck") {
    shouldBeInvalid(HttpHealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1),
      portIndex = Some(0)
    ))
  }

  test("both port and portIndex are not accepted at the same time for a TCP HealthCheck") {
    shouldBeInvalid(TcpHealthCheck(
      port = Some(1),
      portIndex = Some(0)
    ))
  }

  test("port is accepted for a HTTP HealthCheck") {
    shouldBeValid(HttpHealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1)
    ))
  }

  test("port is accepted for a TCP HealthCheck") {
    shouldBeValid(TcpHealthCheck(port = Some(1)))
  }

  test("portIndex is accepted for a HTTP HealthCheck") {
    shouldBeValid(HttpHealthCheck(portIndex = Some(0)))
  }

  test("portIndex is accepted for a TCP HealthCheck") {
    shouldBeValid(TcpHealthCheck(portIndex = Some(0)))
  }

  test("effectivePort with a hard-coded port") {
    import MarathonTestHelper.Implicits._
    val check = new TcpHealthCheck(port = Some(1234))
    val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
    val task = MarathonTestHelper.runningTask("test_id").withHostPorts(Seq(4321))

    assert(check.effectivePort(app, task).contains(1234))
  }

  test("effectivePort with a port index") {
    import MarathonTestHelper.Implicits._
    val check = new TcpHealthCheck(portIndex = Some(0))
    val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
    val task = MarathonTestHelper.runningTask("test_id").withHostPorts(Seq(4321))

    assert(check.effectivePort(app, task).contains(4321))
  }
}
