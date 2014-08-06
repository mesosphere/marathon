package mesosphere.marathon.state

import javax.validation.Validation

import com.google.common.collect.Lists
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.ServiceDefinition
import mesosphere.marathon.api.ModelValidation
import mesosphere.marathon.state.PathId._
import org.apache.mesos.Protos.CommandInfo
import org.scalatest.Matchers

import scala.collection.JavaConverters._

class AppDefinitionTest extends MarathonSpec with Matchers with ModelValidation {

  test("ToProto") {
    val app = AppDefinition(
      id = "play".toPath,
      cmd = "bash foo-*/start -Dhttp.port=$PORT",
      cpus = 4,
      mem = 256,
      instances = 5,
      ports = Seq(8080, 8081),
      executor = "//cmd"
    )

    val proto = app.toProto
    assert("play" == proto.getId)
    assert("bash foo-*/start -Dhttp.port=$PORT" == proto.getCmd.getValue)
    assert(5 == proto.getInstances)
    assert(Lists.newArrayList(8080, 8081) == proto.getPortsList)
    assert("//cmd" == proto.getExecutor)
    assert(4 == getScalarResourceValue(proto, "cpus"), 1e-6)
    assert(256 == getScalarResourceValue(proto, "mem"), 1e-6)
    // TODO test CommandInfo
  }

  test("MergeFromProto") {
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

    assert("play" == mergeResult.id.toString)
    assert(3 == mergeResult.instances)
    assert("//cmd" == mergeResult.executor)
    assert("bash foo-*/start -Dhttp.port=$PORT" == mergeResult.cmd)
  }

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(app: AppDefinition, path: String, template: String) = {
      val violations = checkApp(app, PathId.empty)
      assert(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldNotViolate(app: AppDefinition, path: String, template: String) = {
      val violations = checkApp(app, PathId.empty)
      assert(!violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    val idError = "contains invalid characters (allowed: [a-z0-9]* . and .. in path)"
    val app = AppDefinition(id = "a b".toRootPath)

    shouldViolate(app, "id", idError)
    shouldViolate(app.copy(id = "a#$%^&*b".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "-dash-disallowed-at-start".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "dash-disallowed-at-end-".toRootPath), "id", idError)
    shouldViolate(app.copy(id = "uppercaseLettersNoGood".toRootPath), "id", idError)
    shouldNotViolate(app.copy(id = "ab".toRootPath), "id", idError)

    shouldViolate(
      AppDefinition(id = "test".toPath, instances = -3, ports = Seq(9000, 8080, 9000)),
      "ports",
      "Elements must be unique"
    )

    shouldNotViolate(
      AppDefinition(id = "test".toPath, ports = Seq(0, 0, 8080)),
      "ports",
      "Elements must be unique"
    )

    val correct = AppDefinition(id = "test".toPath)

    shouldNotViolate(
      correct.copy(executor = "//cmd"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = "some/relative/path.mte"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = "/some/absolute/path"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldNotViolate(
      correct.copy(executor = ""),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      correct.copy(executor = "/test/"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )

    shouldViolate(
      correct.copy(executor = "/test//path"),
      "executor",
      "{javax.validation.constraints.Pattern.message}"
    )
  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.jackson.CaseClassModule
    import mesosphere.marathon.api.v2.json.MarathonModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val original = AppDefinition()
    val json = mapper.writeValueAsString(original)
    val readResult = mapper.readValue(json, classOf[AppDefinition])

    assert(readResult == original)

    val json2 =
      """
      {
        "id": "toggle",
        "cmd": "python toggle.py $PORT0",
        "cpus": 0.2,
        "disk": 0.0,
        "healthChecks": [
          {
            "protocol": "COMMAND",
            "command": { "value": "env && http http://$HOST:$PORT0/" }
          }
        ],
        "instances": 2,
        "mem": 32.0,
        "ports": [0],
        "uris": ["http://downloads.mesosphere.io/misc/toggle.tgz"]
      }
      """

    val readResult2 = mapper.readValue(json2, classOf[AppDefinition])
    assert(readResult2.healthChecks.head.command.isDefined)

  }

  def getScalarResourceValue(proto: ServiceDefinition, name: String) = {
    proto.getResourcesList.asScala
      .find(_.getName == name)
      .get.getScalar.getValue
  }
}
