package mesosphere.marathon.core.health

import com.wix.accord.validate
import mesosphere.marathon.Protos
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state._
import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.duration._

class HealthCheckTest extends MarathonSpec {

  test("ToProto Marathon HTTP HealthCheck with portIndex") {
    val healthCheck = MarathonHttpHealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(PortReference(0)),
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

  test("ToProto Marathon HTTP HealthCheck with port") {
    val healthCheck = MarathonHttpHealthCheck(
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

  test("ToProto Marathon TCP HealthCheck with portIndex") {
    val healthCheck = MarathonTcpHealthCheck(
      portIndex = Some(PortReference(1)),
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

  test("ToProto Marathon TCP HealthCheck with port") {
    val healthCheck = MarathonTcpHealthCheck(
      port = Some(80),
      gracePeriod = 7.seconds,
      interval = 35.seconds,
      maxConsecutiveFailures = 10
    )

    val proto = healthCheck.toProto

    assert(Protocol.TCP == proto.getProtocol)
    assert(80 == proto.getPort)
    assert(7 == proto.getGracePeriodSeconds)
    assert(35 == proto.getIntervalSeconds)
    assert(10 == proto.getMaxConsecutiveFailures)
  }

  test("FromProto Marathon HTTP HealthCheck with portIndex") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setPortIndex(0)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck.fromProto(proto)

    val expectedResult = MarathonHttpHealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(PortReference(0)),
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      port = None
    )

    assert(mergeResult == expectedResult)
  }

  test("FromProto Marathon HTTP HealthCheck with port") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .setPort(12345)
      .build

    val mergeResult = HealthCheck.fromProto(proto)

    val expectedResult = MarathonHttpHealthCheck(
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

  test("FromProto Marathon HTTP HealthCheck with neither port nor portIndex") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck.fromProto(proto)

    val expectedResult = MarathonHttpHealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = Some(PortReference(0)),
      gracePeriod = 10.seconds,
      interval = 60.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10,
      port = None
    )

    assert(mergeResult == expectedResult)
  }

  test("FromProto Marathon TCP HealthCheck with portIndex") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(Protocol.TCP)
      .setPortIndex(1)
      .setGracePeriodSeconds(7)
      .setIntervalSeconds(35)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck.fromProto(proto)

    val expectedResult = MarathonTcpHealthCheck(
      portIndex = Some(PortReference(1)),
      gracePeriod = 7.seconds,
      interval = 35.seconds,
      timeout = 10.seconds,
      maxConsecutiveFailures = 10
    )

    assert(mergeResult == expectedResult)
  }

  test("FromProto Marathon HTTPS HealthCheck") {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTPS)
      .setPortIndex(0)
      .setGracePeriodSeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .setMaxConsecutiveFailures(10)
      .build

    val mergeResult = HealthCheck.fromProto(proto)

    val expectedResult = MarathonHttpHealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTPS,
      portIndex = Some(PortReference(0)),
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
    val original = MesosCommandHealthCheck(command = Command("true"))
    val json = toJson(original)
    val readResult = fromJson(json)
    assert(readResult == original)
  }

  test("A default HealthCheck should be valid") {
    // portIndex is added in the Format conversion of the app
    shouldBeValid(MarathonHttpHealthCheck(portIndex = Some(PortReference(0))))
  }

  test("both port and portIndex are not accepted at the same time for a HTTP HealthCheck") {
    shouldBeInvalid(MarathonHttpHealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1),
      portIndex = Some(PortReference(0))
    ))
  }

  test("both port and portIndex are not accepted at the same time for a TCP HealthCheck") {
    shouldBeInvalid(MarathonTcpHealthCheck(
      port = Some(1),
      portIndex = Some(PortReference(0))
    ))
  }

  test("port is accepted for a HTTP HealthCheck") {
    shouldBeValid(MarathonHttpHealthCheck(
      protocol = Protocol.HTTP,
      port = Some(1)
    ))
  }

  test("port is accepted for a TCP HealthCheck") {
    shouldBeValid(MarathonTcpHealthCheck(port = Some(1)))
  }

  test("portIndex is accepted for a HTTP HealthCheck") {
    shouldBeValid(MarathonHttpHealthCheck(portIndex = Some(PortReference(0))))
  }

  test("portIndex is accepted for a TCP HealthCheck") {
    shouldBeValid(MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))
  }

  test("effectivePort with a hard-coded port") {
    import mesosphere.marathon.test.MarathonTestHelper.Implicits._
    val check = new MarathonTcpHealthCheck(port = Some(1234))
    val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
    val task = TestTaskBuilder.Helper.runningTaskForApp(app.id).withHostPorts(Seq(4321))

    assert(check.effectivePort(app, task) == 1234)
  }

  test("effectivePort with a port index") {
    import MarathonTestHelper.Implicits._
    val check = new MarathonTcpHealthCheck(portIndex = Some(PortReference(0)))
    val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
    val task = {
      val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(app.id)
      val hostName = "hostName"
      val hostPorts = Seq(4321)
      t.copy(status = t.status.copy(networkInfo = NetworkInfo(app, hostName, hostPorts, ipAddresses = None)))
    }

    assert(check.effectivePort(app, task) == 4321)
  }

  private[this] def shouldBeInvalid(hc: HealthCheck): Unit = {
    assert(validate(hc).isFailure)
  }

  private[this] def shouldBeValid(hc: HealthCheck): Unit = {
    val result = validate(hc)
    assert(result.isSuccess, s"violations: ${ValidationHelper.getAllRuleConstrains(result)}")
  }
}
