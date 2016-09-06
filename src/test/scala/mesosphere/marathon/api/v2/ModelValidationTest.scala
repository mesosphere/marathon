package mesosphere.marathon.api.v2

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.json.GroupUpdate
import mesosphere.marathon.state.Container.Docker.PortMapping
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network
import org.scalatest.{ BeforeAndAfterAll, Matchers, OptionValues }
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
    val appA = createServicePortApp("/a".toPath, 0)
    val appB = createServicePortApp("/b".toPath, 0)
    val appC = createServicePortApp("/c".toPath, 0)
    val group = Group("/".toPath, Map(
      appA.id -> appA,
      appB.id -> appB,
      appC.id -> appC
    ))

    val failedResult = Group.validRootGroup(maxApps = Some(2)).apply(group)
    failedResult.isFailure should be(true)
    ValidationHelper.getAllRuleConstrains(failedResult)
      .find(v => v.message.contains("This Marathon instance may only handle up to 2 Apps!")) should be ('defined)

    val successfulResult = Group.validRootGroup(maxApps = Some(10)).apply(group)
    successfulResult.isSuccess should be(true)
  }

  test("Model validation should allow new apps that do not conflict with service ports in existing apps") {

    val existingApp = createServicePortApp("/app1".toPath, 3200)
    val conflictingApp = createServicePortApp("/app2".toPath, 3201)

    val group = Group(id = PathId.empty, apps = Map(existingApp.id -> existingApp, conflictingApp.id -> conflictingApp))
    val result = validate(group)(Group.validRootGroup(maxApps = None))

    result.isSuccess should be(true)
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
    val validApp = AppDefinition("/test/group1/valid".toPath, cmd = Some("foo"));
    val invalidApp = AppDefinition("/test/group1/invalid".toPath)
    val group = Group("/test".toPath, groups = Set(
      Group("/test/group1".toPath, Map(
        validApp.id -> validApp,
        invalidApp.id -> invalidApp)
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

  test("PortDefinition should be allowed to contain tcp and udp as protocol.") {
    val validApp = AppDefinition("/test/app".toPath, cmd = Some("foo"), portDefinitions = Seq(PortDefinition(port = 80, protocol = "tcp,udp")))

    val group = Group("/test".toPath, apps = Map(validApp.id -> validApp))

    val result = validate(group)(Group.validRootGroup(maxApps = None))
    result.isSuccess should be(true)
  }

  private def createServicePortApp(id: PathId, servicePort: Int) =
    AppDefinition(
      id,
      container = Some(Docker(
        image = "demothing",
        network = Some(Network.BRIDGE),
        portMappings = Some(Seq(PortMapping(2000, Some(0), servicePort = servicePort)))
      ))
    )

  case class ImportantTitle(name: String)

  implicit val mrImportantValidator = validator[ImportantTitle] { m =>
    m.name is equalTo("Dr.")
    m.name is notEmpty
  }
}
