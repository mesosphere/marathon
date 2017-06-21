package mesosphere.marathon
package core.health

import com.wix.accord.validate
import mesosphere.UnitTest
import mesosphere.marathon.Protos.HealthCheckDefinition.Protocol
import mesosphere.marathon.api.v2.ValidationHelper
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ LegacyAppInstance, TestInstanceBuilder, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.raml.{ AppHealthCheck, Raml }
import mesosphere.marathon.state._
import mesosphere.marathon.test.MarathonTestHelper
import play.api.libs.json.Json

import scala.concurrent.duration._

class HealthCheckTest extends UnitTest {
  "HealthCheck" should {
    "ToProto Marathon HTTP HealthCheck with portIndex" in {
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

    "ToProto Marathon HTTP HealthCheck with port" in {
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

    "ToProto Marathon TCP HealthCheck with portIndex" in {
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

    "ToProto Marathon TCP HealthCheck with port" in {
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

    "FromProto Marathon HTTP HealthCheck with portIndex" in {
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

    "FromProto Marathon HTTP HealthCheck with port" in {
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

    "FromProto Marathon HTTP HealthCheck with neither port nor portIndex" in {
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

    "FromProto Marathon TCP HealthCheck with portIndex" in {
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

    "FromProto Marathon HTTPS HealthCheck" in {
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

    def toJson(healthCheck: HealthCheck): String = {
      val ramlObj: AppHealthCheck = Raml.toRaml(healthCheck)
      Json.prettyPrint(AppHealthCheck.playJsonFormat.writes(ramlObj))
    }
    def fromJson(json: String): HealthCheck = {
      val parsed: AppHealthCheck = Json.parse(json).as[AppHealthCheck]
      Raml.fromRaml(parsed)
    }

    "SerializationRoundtrip empty" in {
      val original = MesosCommandHealthCheck(command = Command("true"))
      val json = toJson(original)
      val readResult = fromJson(json)
      assert(readResult == original)
    }

    "A default HealthCheck should be valid" in {
      // portIndex is added in the Format conversion of the app
      shouldBeValid(MarathonHttpHealthCheck(portIndex = Some(PortReference(0))))
    }

    "both port and portIndex are not accepted at the same time for a HTTP HealthCheck" in {
      shouldBeInvalid(MarathonHttpHealthCheck(
        protocol = Protocol.HTTP,
        port = Some(1),
        portIndex = Some(PortReference(0))
      ))
    }

    "both port and portIndex are not accepted at the same time for a TCP HealthCheck" in {
      shouldBeInvalid(MarathonTcpHealthCheck(
        port = Some(1),
        portIndex = Some(PortReference(0))
      ))
    }

    "port is accepted for a HTTP HealthCheck" in {
      shouldBeValid(MarathonHttpHealthCheck(
        protocol = Protocol.HTTP,
        port = Some(1)
      ))
    }

    "port is accepted for a TCP HealthCheck" in {
      shouldBeValid(MarathonTcpHealthCheck(port = Some(1)))
    }

    "portIndex is accepted for a HTTP HealthCheck" in {
      shouldBeValid(MarathonHttpHealthCheck(portIndex = Some(PortReference(0))))
    }

    "portIndex is accepted for a TCP HealthCheck" in {
      shouldBeValid(MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))
    }

    "effectivePort with a hard-coded port" in {
      import mesosphere.marathon.test.MarathonTestHelper.Implicits._
      val check = new MarathonTcpHealthCheck(port = Some(1234))
      val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskWithBuilder().taskRunning()
        .withNetworkInfo(hostPorts = Seq(4321))
        .build().getInstance()

      assert(check.effectivePort(app, instance) == Option(1234))
    }

    "effectivePort with a port index" in {
      import MarathonTestHelper.Implicits._
      val check = new MarathonTcpHealthCheck(portIndex = Some(PortReference(0)))
      val app = MarathonTestHelper.makeBasicApp().withPortDefinitions(Seq(PortDefinition(0)))
      val hostName = "hostName"
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), attributes = Nil)
      val task = {
        val t: Task.LaunchedEphemeral = TestTaskBuilder.Helper.runningTaskForApp(app.id)
        val hostPorts = Seq(4321)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = LegacyAppInstance(task, agentInfo, unreachableStrategy = UnreachableStrategy.default())

      assert(check.effectivePort(app, instance) == Option(4321))
    }
  }
  private[this] def shouldBeInvalid(hc: HealthCheck): Unit = {
    assert(validate(hc).isFailure)
  }

  private[this] def shouldBeValid(hc: HealthCheck): Unit = {
    val result = validate(hc)
    assert(result.isSuccess, s"violations: ${ValidationHelper.getAllRuleConstrains(result)}")
  }
}
