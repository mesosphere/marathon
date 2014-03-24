package mesosphere.marathon.api.v1

import org.junit.Test
import org.junit.Assert._
import com.google.common.collect.Lists
import scala.collection.JavaConverters._
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.state.Timestamp
import org.apache.mesos.Protos.CommandInfo
import javax.validation.Validation

/**
 * @author Tobi Knaup
 */
class AppDefinitionTest {

  @Test
  def testToProto() {
    val app = AppDefinition(
      id = "play",
      cmd = "bash foo-*/start -Dhttp.port=$PORT",
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd"
    )

    val proto = app.toProto
    assertEquals("play", proto.getId)
    assertEquals("bash foo-*/start -Dhttp.port=$PORT", proto.getCmd.getValue)
    assertEquals(5, proto.getInstances)
    assertEquals(Lists.newArrayList(8080, 8081), proto.getPortsList)
    assertEquals("//cmd", proto.getExecutor)
    assertEquals(4, getScalarResourceValue(proto, "cpus"), 1e-6)
    assertEquals(256, getScalarResourceValue(proto, "mem"), 1e-6)
    // TODO test CommandInfo
  }

  @Test
  def testMergeFromProto() {
    val cmd = CommandInfo.newBuilder
      .setValue("bash foo-*/start -Dhttp.port=$PORT")

    val proto = ServiceDefinition.newBuilder
      .setId("play")
      .setCmd(cmd)
      .setInstances(3)
      .setExecutor("//cmd")
      .setVersion(Timestamp.now.toString)
      .build

    val mergeResult = AppDefinition().mergeFromProto(proto)

    assertEquals("play", mergeResult.id)
    assertEquals(3, mergeResult.instances)
    assertEquals("//cmd", mergeResult.executor)
    assertEquals("bash foo-*/start -Dhttp.port=$PORT", mergeResult.cmd)
  }

  @Test
  def testValidation() {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def should(assertion: (Boolean) => Unit, app: AppDefinition, path: String, template: String) = {
      val violations = validator.validate(app).asScala
      assertion(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldViolate(app: AppDefinition, path: String, template: String) =
      should(assertTrue, app, path, template)

    def shouldNotViolate(app: AppDefinition, path: String, template: String) =
      should(assertFalse, app, path, template)

    val app = AppDefinition(id = "a b")
    shouldViolate(app, "id", "{javax.validation.constraints.Pattern.message}")

    shouldViolate(
      app.copy(id = "a#$%^&*b"),
      "id",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      app.copy(id = "ab"),
      "id",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      AppDefinition(id = "test", instances = -3),
      "instances",
      "{javax.validation.constraints.Min.message}"
    )

    shouldViolate(
      AppDefinition(id = "test", instances = -3, ports = Seq(9000, 8080, 9000)),
      "ports",
      "Elements must be unique"
    )

    shouldNotViolate(
      AppDefinition(id = "test", ports = Seq(0, 0, 8080)),
      "ports",
      "Elements must be unique"
    )

  }

  @Test
  def testSerializationRoundtrip() {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)

    val original = AppDefinition()
    val json = mapper.writeValueAsString(original)
    val readResult = mapper.readValue(json, classOf[AppDefinition])

    assertTrue(readResult == original)
  }

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }
}
