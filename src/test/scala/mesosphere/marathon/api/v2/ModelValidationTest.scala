package mesosphere.marathon.api.v2

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.json.GroupUpdate
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.scalatest.{ BeforeAndAfterAll, Matchers, OptionValues }

import mesosphere.marathon.api.v2.Validation._
import play.api.libs.json.{ JsObject, Json }

import scala.collection.immutable.Seq

class ModelValidationTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll
    with OptionValues {

  test("A group update should pass validation") {
    val update = GroupUpdate(id = Some("/a/b/c".toPath))

    validate(update).isSuccess should be(true)
  }

  test("A group can not be updated to have more than the configured number of apps") {
    val group = Group("/".toPath, Set(
      createServicePortApp("/a".toPath, 0),
      createServicePortApp("/b".toPath, 0),
      createServicePortApp("/c".toPath, 0)
    ))

    val failedResult = Group.validRootGroup(maxApps = Some(2)).apply(group)
    failedResult.isFailure should be(true)
    ValidationHelper.getAllRuleConstrains(failedResult)
      .find(v => v.message.contains("This Marathon instance may only handle up to 2 Apps!")) should be ('defined)

    val successfulResult = Group.validRootGroup(maxApps = Some(10)).apply(group)
    successfulResult.isSuccess should be(true)
  }

  test("Model validation should catch new apps that conflict with service ports in existing apps") {
    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = createServicePortApp("/app2".toPath, 3200)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)(Group.validRootGroup(maxApps = None))

    ValidationHelper.getAllRuleConstrains(result).exists(v =>
      v.message == "Requested service port 3200 conflicts with a service port in app /app2") should be(true)
  }

  test("Model validation should allow new apps that do not conflict with service ports in existing apps") {

    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = createServicePortApp("/app2".toPath, 3201)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)(Group.validRootGroup(maxApps = None))

    result.isSuccess should be(true)
  }

  test("Model validation should check for application conflicts") {
    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = existingApp.copy(id = "/app2".toPath)

    val group = Group(id = PathId.empty, apps = Set(existingApp, conflictingApp))
    val result = validate(group)(Group.validRootGroup(maxApps = None))

    ValidationHelper.getAllRuleConstrains(result).exists(v =>
      v.message == "Requested service port 3200 conflicts with a service port in app /app2") should be(true)
  }

  test("Multiple errors within one field of a validator should be grouped into one array") {
    val empty = ImportantTitle("")

    validate(empty) match {
      case Success => fail()
      case f: Failure =>
        val errors = (Json.toJson(f) \ "details").as[Seq[JsObject]]
        errors should have size 1
        (errors.head \ "path").as[String] should be("/name")
        (errors.head \ "errors").as[Seq[String]] should have size 2
    }
  }

  test("Validators should not produce 'value' string at the end of description.") {
    val group = Group("/test".toPath, groups = Set(
      Group("/test/group1".toPath, Set(
        AppDefinition("/test/group1/valid".toPath, cmd = Some("foo")),
        AppDefinition("/test/group1/invalid".toPath))
      ),
      Group("/test/group2".toPath)))

    validate(group)(Group.validRootGroup(maxApps = None)) match {
      case Success => fail()
      case f: Failure =>
        val errors = (Json.toJson(f) \ "details").as[Seq[JsObject]]
        errors should have size 1
        (errors.head \ "path").as[String] should be("/groups(0)/apps(1)")
    }
  }

  private def createServicePortApp(id: PathId, servicePort: Int) =
    AppDefinition(
      id,
      container = Some(Container(
        docker = Some(Docker(
          image = "demothing",
          network = Some(Network.BRIDGE),
          portMappings = Some(Seq(PortMapping(2000, servicePort = servicePort)))
        ))
      ))
    )

  case class ImportantTitle(name: String)

  implicit val mrImportantValidator = validator[ImportantTitle] { m =>
    m.name is equalTo("Dr.")
    m.name is notEmpty
  }
}
