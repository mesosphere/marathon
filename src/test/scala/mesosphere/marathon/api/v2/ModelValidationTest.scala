package mesosphere.marathon
package api.v2

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.raml.GroupUpdate
import mesosphere.marathon.core.pod.BridgeNetwork
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import play.api.libs.json.{ JsObject, Json }

import scala.collection.immutable.Seq

object ModelValidationTest {

  implicit val groupUpdateValidator: Validator[GroupUpdate] = Group.validNestedGroupUpdateWithBase(PathId.empty)

  case class ImportantTitle(name: String)

  private implicit val mrImportantValidator: Validator[ImportantTitle] = validator[ImportantTitle] { m =>
    m.name is equalTo("Dr.")
    m.name is notEmpty
  }

  def createServicePortApp(id: PathId, servicePort: Int) =
    AppDefinition(
      id,
      networks = Seq(BridgeNetwork()),
      container = Some(Docker(
        image = "demothing",
        portMappings = Seq(PortMapping(2000, Some(0), servicePort = servicePort))
      ))
    )
}

class ModelValidationTest extends UnitTest with GroupCreation {

  import ModelValidationTest._

  "ModelValidation" should {
    "A group update should pass validation" in {
      val update = GroupUpdate(id = Some("/a/b/c"))

      validate(update).isSuccess should be(true)
    }

    "Model validation should allow new apps that do not conflict with service ports in existing apps" in {

      val existingApp = createServicePortApp("/app1".toPath, 3200)
      val conflictingApp = createServicePortApp("/app2".toPath, 3201)

      val rootGroup = createRootGroup(apps = Map(existingApp.id -> existingApp, conflictingApp.id -> conflictingApp))
      val result = validate(rootGroup)(RootGroup.rootGroupValidator(Set()))

      result.isSuccess should be(true)
    }

    "Multiple errors within one field of a validator should be grouped into one array" in {
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

    "Validators should not produce 'value' string at the end of description." in {
      val validApp = AppDefinition("/test/group1/valid".toPath, cmd = Some("foo"))
      val invalidApp = AppDefinition("/test/group1/invalid".toPath)
      val rootGroup = createRootGroup(groups = Set(createGroup("/test".toPath, groups = Set(
        createGroup("/test/group1".toPath, Map(
          validApp.id -> validApp,
          invalidApp.id -> invalidApp)
        ),
        createGroup("/test/group2".toPath)))))

      validate(rootGroup)(RootGroup.rootGroupValidator(Set())) match {
        case Success => fail()
        case f: Failure =>
          val errors = (Json.toJson(f) \ "details").as[Seq[JsObject]]
          errors should have size 1
          (errors.head \ "path").as[String] should be("/groups(0)/groups(0)/apps(1)")
      }
    }

    "PortDefinition should be allowed to contain tcp and udp as protocol." in {
      val validApp = AppDefinition("/test/app".toPath, cmd = Some("foo"), portDefinitions = Seq(PortDefinition(port = 80, protocol = "udp,tcp")))

      val rootGroup = createRootGroup(groups = Set(createGroup("/test".toPath, apps = Map(validApp.id -> validApp))))

      val result = validate(rootGroup)(RootGroup.rootGroupValidator(Set()))
      result.isSuccess should be(true)
    }
  }
}