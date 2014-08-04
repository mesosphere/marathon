package mesosphere.marathon.api.v2

import javax.validation.Validation
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import mesosphere.marathon.{ ContainerInfo, MarathonSpec }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.Timestamp

class AppUpdateTest extends MarathonSpec {

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(update: AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldNotViolate(update: AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(!violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    val update = AppUpdate()

    shouldViolate(
      update.copy(ports = Some(Seq(9000, 8080, 9000))),
      "ports",
      "Elements must be unique"
    )

  }

  test("SerializationRoundtrip") {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.module.scala.DefaultScalaModule
    import mesosphere.marathon.api.v2.json.MarathonModule
    import mesosphere.jackson.CaseClassModule

    val mapper = new ObjectMapper
    mapper.registerModule(DefaultScalaModule)
    mapper.registerModule(new MarathonModule)
    mapper.registerModule(CaseClassModule)

    val update0 = AppUpdate(container = Some(ContainerInfo()))
    val json0 = mapper.writeValueAsString(update0)
    val readResult0 = mapper.readValue(json0, classOf[AppUpdate])
    assert(readResult0 == update0)

    val update1 = AppUpdate(
      cmd = Some("sleep 60"),
      user = Some("nobody"),
      env = Some(Map("LANG" -> "en-US")),
      instances = Some(16),
      cpus = Some(2.0),
      mem = Some(256.0),
      disk = Some(1024.0),
      executor = Some("http://dl.corp.org/executors/some.executor"),
      constraints = Some(Set()),
      uris = Some(Seq("http://dl.corp.org/prodX-1.2.3.tgz")),
      ports = Some(Seq(0, 0)),
      backoff = Some(2.seconds),
      backoffFactor = Some(1.2),
      container = Some(
        ContainerInfo(image = "docker:///group/image", options = Seq("-h"))
      ),
      healthChecks = Some(Set[HealthCheck]()),
      version = Some(Timestamp.now)
    )
    val json1 = mapper.writeValueAsString(update1)
    val readResult1 = mapper.readValue(json1, classOf[AppUpdate])
    assert(readResult1 == update1)

    println(json0)

    val update2 = AppUpdate(container = Some(ContainerInfo()))
    val json2 = """
      {
        "cmd": null,
        "user": null,
        "env": null,
        "instances": null,
        "cpus": null,
        "mem": null,
        "disk": null,
        "executor": null,
        "constraints": null,
        "uris": null,
        "ports": null,
        "backoffSeconds": null,
        "backoffFactor": null,
        "container": null,
        "healthChecks": null,
        "version": null
      }
    """
    val readResult2 = mapper.readValue(json2, classOf[AppUpdate])
    assert(readResult2 == update2)

    val update3 = AppUpdate()
    val json3 = "{}"
    val readResult3 = mapper.readValue(json3, classOf[AppUpdate])
    assert(readResult3 == update3)

  }
}
