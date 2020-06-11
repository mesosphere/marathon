package mesosphere.marathon
package api.v2

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.core.pod.BridgeNetwork
import mesosphere.marathon.raml.GroupUpdate
import mesosphere.marathon.state.Container._
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import mesosphere.{UnitTest, ValidationTestLike}
import play.api.libs.json.{JsObject, Json}

import scala.collection.immutable.Seq

object ModelValidationTest {

  case class ImportantTitle(name: String)

  private implicit val mrImportantValidator: Validator[ImportantTitle] = validator[ImportantTitle] { m =>
    m.name is equalTo("Dr.")
    m.name is notEmpty
  }

  def createServicePortApp(id: AbsolutePathId, servicePort: Int) =
    AppDefinition(
      id,
      role = "*",
      networks = Seq(BridgeNetwork()),
      container = Some(
        Docker(
          image = "demothing",
          portMappings = Seq(PortMapping(2000, Some(0), servicePort = servicePort))
        )
      )
    )
}

class ModelValidationTest extends UnitTest with GroupCreation with ValidationTestLike {

  val emptyConfig = AllConf.withTestConfig()

  import ModelValidationTest._

  "ModelValidation" should {
    "An empty group update should pass validation" in {
      implicit val groupUpdateValidator: Validator[GroupUpdate] =
        Group.validNestedGroupUpdateWithBase(PathId.root, RootGroup.empty(), false)
      val update = GroupUpdate(id = Some("/a/b/c"))

      validate(update).isSuccess should be(true)
    }

    "Model validation should allow new apps that do not conflict with service ports in existing apps" in {

      val existingApp = createServicePortApp(AbsolutePathId("/app1"), 3200)
      val conflictingApp = createServicePortApp(AbsolutePathId("/app2"), 3201)

      val rootGroup = createRootGroup(apps = Map(existingApp.id -> existingApp, conflictingApp.id -> conflictingApp))
      val result = validate(rootGroup)(RootGroup.validRootGroup(emptyConfig))

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
      val validApp = AppDefinition(AbsolutePathId("/test/group1/valid"), cmd = Some("foo"), role = "*")
      val invalidApp = AppDefinition(AbsolutePathId("/test/group1/invalid"), role = "*")
      val rootGroup = createRootGroup(
        groups = Set(
          createGroup(
            AbsolutePathId("/test"),
            groups = Set(
              createGroup(AbsolutePathId("/test/group1"), Map(validApp.id -> validApp, invalidApp.id -> invalidApp), validate = false),
              createGroup(AbsolutePathId("/test/group2"), validate = false)
            ),
            validate = false
          )
        ),
        validate = false
      )

      validate(rootGroup)(RootGroup.validRootGroup(emptyConfig)) should haveViolations(
        "/apps//test/group1/invalid" -> "AppDefinition must either contain one of 'cmd' or 'args', and/or a 'container'."
      )
    }

    "PortDefinition should be allowed to contain tcp and udp as protocol." in {
      val validApp = AppDefinition(
        AbsolutePathId("/test/app"),
        cmd = Some("foo"),
        portDefinitions = Seq(PortDefinition(port = 80, protocol = "udp,tcp")),
        role = "*"
      )

      val rootGroup = createRootGroup(groups = Set(createGroup(AbsolutePathId("/test"), apps = Map(validApp.id -> validApp))))

      val result = validate(rootGroup)(RootGroup.validRootGroup(emptyConfig))
      result.isSuccess should be(true)
    }
  }
}
