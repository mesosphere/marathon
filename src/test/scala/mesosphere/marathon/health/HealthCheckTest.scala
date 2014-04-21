package mesosphere.marathon.health

import mesosphere.marathon.Protos
import Protos.HealthCheckDefinition.Protocol

import org.junit.Test
import org.junit.Assert._

import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._

import java.util.concurrent.TimeUnit.SECONDS
import javax.validation.Validation

class HealthCheckTest {

  @Test
  def testToProto() {
    val healthCheck = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      acceptableResponses = Some(Set(200)),
      portIndex = 0,
      initialDelay = FiniteDuration(10, SECONDS),
      interval = FiniteDuration(60, SECONDS)
    )

    val proto = healthCheck.toProto

    assertEquals("/health", proto.getPath)
    assertEquals(Protocol.HTTP, proto.getProtocol)
    assertEquals(Set(200), proto.getAcceptableResponsesList.asScala.toSet)
    assertEquals(0, proto.getPortIndex)
    assertEquals(10, proto.getInitialDelaySeconds)
    assertEquals(60, proto.getIntervalSeconds)
  }

  @Test
  def testToProtoTcp() {
    val healthCheck = HealthCheck(
      protocol = Protocol.TCP,
      portIndex = 1,
      initialDelay = FiniteDuration(7, SECONDS),
      interval = FiniteDuration(35, SECONDS)
    )

    val proto = healthCheck.toProto

    assertEquals(Protocol.TCP, proto.getProtocol)
    assertEquals(1, proto.getPortIndex)
    assertEquals(7, proto.getInitialDelaySeconds)
    assertEquals(35, proto.getIntervalSeconds)
  }

  @Test
  def testMergeFromProto() {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setPath("/health")
      .setProtocol(Protocol.HTTP)
      .addAllAcceptableResponses(Set(200).map(i => i: Integer).asJava)
      .setPortIndex(0)
      .setInitialDelaySeconds(10)
      .setIntervalSeconds(60)
      .setTimeoutSeconds(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/health"),
      protocol = Protocol.HTTP,
      acceptableResponses = Some(Set(200)),
      portIndex = 0,
      initialDelay = FiniteDuration(10, SECONDS),
      interval = FiniteDuration(60, SECONDS),
      timeout = FiniteDuration(10, SECONDS)
    )

    assertEquals(mergeResult, expectedResult)
  }

  @Test
  def testMergeFromProtoTcp() {
    val proto = Protos.HealthCheckDefinition.newBuilder
      .setProtocol(Protocol.TCP)
      .setPortIndex(1)
      .setInitialDelaySeconds(7)
      .setIntervalSeconds(35)
      .setTimeoutSeconds(10)
      .build

    val mergeResult = HealthCheck().mergeFromProto(proto)

    val expectedResult = HealthCheck(
      path = Some("/"),
      protocol = Protocol.TCP,
      portIndex = 1,
      initialDelay = FiniteDuration(7, SECONDS),
      interval = FiniteDuration(35, SECONDS),
      timeout = FiniteDuration(10, SECONDS)
    )

    assertEquals(mergeResult, expectedResult)
  }

  @Test
  def testSerializationRoundtrip() {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)

    val original = HealthCheck()
    val json = mapper.writeValueAsString(original)
    val readResult = mapper.readValue(json, classOf[HealthCheck])

    assertEquals(readResult, original)
  }

}