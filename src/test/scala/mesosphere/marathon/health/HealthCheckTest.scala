package mesosphere.marathon.health

import mesosphere.marathon.{MarathonSpec, Protos}
import Protos.HealthCheckDefinition.Protocol
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit.SECONDS

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
      path = Some("/"),
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

    val original = HealthCheck()
    val json = mapper.writeValueAsString(original)
    val readResult = mapper.readValue(json, classOf[HealthCheck])

    assert(readResult == original)
  }

}
