package mesosphere.marathon.health

import mesosphere.marathon.{ MarathonSpec, Protos }
import mesosphere.marathon.state.Command
import mesosphere.jackson.CaseClassModule
import Protos.HealthCheckDefinition.Protocol
import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit.SECONDS
import javax.validation.Validation

class HealthCheckTest extends MarathonSpec {

  test("ToProto") {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = 0,
      gracePeriod = FiniteDuration(10, SECONDS),
      interval = FiniteDuration(60, SECONDS),
      maxConsecutiveFailures = 0
    )

    val proto = healthCheck.toProto

    assert("/health" == proto.getPath)
    assert(Protocol.HTTP == proto.getProtocol)
    assert(0 == proto.getPortIndex)
    assert(10 == proto.getGracePeriodSeconds)
    assert(60 == proto.getIntervalSeconds)
    assert(0 == proto.getMaxConsecutiveFailures)
  }

  test("ToProtoTcp") {
    val healthCheck = HealthCheck(
      protocol = Protocol.TCP,
      portIndex = 1,
      gracePeriod = FiniteDuration(7, SECONDS),
      interval = FiniteDuration(35, SECONDS),
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
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      portIndex = 0,
      gracePeriod = FiniteDuration(10, SECONDS),
      interval = FiniteDuration(60, SECONDS),
      timeout = FiniteDuration(10, SECONDS),
      maxConsecutiveFailures = 10
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
      gracePeriod = FiniteDuration(7, SECONDS),
      interval = FiniteDuration(35, SECONDS),
      timeout = FiniteDuration(10, SECONDS),
      maxConsecutiveFailures = 10
    )

    assert(mergeResult == expectedResult)
  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    {
      val original = HealthCheck()
      val json = mapper.writeValueAsString(original)
      val readResult = mapper.readValue(json, classOf[HealthCheck])
      assert(readResult == original)
    }

    {
      val json =
        """
        {
          "protocol": "COMMAND",
          "portIndex": 0,
          "command": { "value": "echo healthy" },
          "gracePeriodSeconds": 15,
          "intervalSeconds": 10,
          "timeoutSeconds": 20,
          "maxConsecutiveFailures": 3
        }
        """
      val expected =
        HealthCheck(
          protocol = Protocol.COMMAND,
          command = Some(Command("echo healthy"))
        )
      val readResult = mapper.readValue(json, classOf[HealthCheck])
      assert(readResult == expected)
    }

    {
      val json =
        """
        {
          "protocol": "COMMAND",
          "portIndex": 0,
          "command": { "value": "echo healthy" },
          "gracePeriodSeconds": 15,
          "intervalSeconds": 10,
          "timeoutSeconds": 20,
          "maxConsecutiveFailures": 3
        }
        """
      val expected =
        HealthCheck(
          protocol = Protocol.COMMAND,
          command = Some(Command("echo healthy")),
          path = Some("/")
        )
      val readResult = mapper.readValue(json, classOf[HealthCheck])
      assert(readResult == expected)
    }

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
