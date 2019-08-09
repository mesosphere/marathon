package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord.{Failure, Result, Success}
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.api.v2.Validation.ConstraintViolation
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import play.api.libs.json.{JsString, Json}

import scala.collection.immutable.Seq

class DVDIProviderRootGroupValidationTest extends UnitTest with GroupCreation {

  "DVDIProviderRootGroupValidation" should {
    "two volumes with different names should not result in an error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = PathId("/nested/foo1"), volumeName = "vol1")
      val app2 = f.appWithDVDIVolume(appId = PathId("/nested/foo2"), volumeName = "vol2")
      val rootGroup = createRootGroup(
        groups = Set(
          createGroup(
            id = AbsolutePathId("/nested"),
            apps = Map(
              app1.id -> app1,
              app2.id -> app2
            ),
            validate = false
          )
        ),
        validate = false
      )

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    "two volumes with same name should not result in an error" in {
      val f = new Fixture
      Given("a root group with two apps and same volumes")
      val app1 = f.appWithDVDIVolume(appId = PathId("/nested/app1"), volumeName = "vol")
      val app2 = f.appWithDVDIVolume(appId = PathId("/nested/app2"), volumeName = "vol")
      val rootGroup = createRootGroup(
        groups = Set(
          createGroup(
            id = AbsolutePathId("/nested"),
            apps = Map(
              app1.id -> app1,
              app2.id -> app2
            ),
            validate = false
          )
        ),
        validate = false
      )

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    "volumes with parameters in name should not result in an error" in {
      val f = new Fixture
      Given("a root group with an app and a volume")
      val app1 = f.appWithDVDIVolume(appId = PathId("/nested/app1"), volumeName = "name=teamvolumename,repl=1,secure=true,secret_key=volume-secret-key-team")
      val rootGroup = createRootGroup(
        groups = Set(
          createGroup(
            id = AbsolutePathId("/nested"),
            apps = Map(
              app1.id -> app1
            ),
            validate = false
          )
        ),
        validate = false
      )

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    class Fixture {
      def appWithDVDIVolume(appId: PathId, volumeName: String, provider: String = DVDIProvider.name): AppDefinition = {
        AppDefinition(
          id = appId,
          role = "*",
          cmd = Some("sleep 123"),
          upgradeStrategy = UpgradeStrategy.forResidentTasks,
          container = Some(
            Container.Mesos(
              volumes = Seq(
                VolumeWithMount(
                  volume = ExternalVolume(
                    name = None,
                    external = ExternalVolumeInfo(
                      name = volumeName,
                      provider = provider,
                      options = Map(
                        DVDIProvider.driverOption -> "rexray"))),
                  mount = VolumeMount(
                    volumeName = None,
                    mountPath = "ignoreme",
                    readOnly = false))))))
      }

      def jsonResult(result: Result): String = {
        Json.prettyPrint(
          result match {
            case Success => JsString("Success")
            case f: Failure => Json.toJson(f)(Validation.failureWrites)
          }
        )
      }

      def checkResult(rootGroup: RootGroup, expectedViolations: Set[ConstraintViolation]): Unit = {
        val expectFailure = expectedViolations.nonEmpty

        When("validating the root group")
        val result = DVDIProviderValidations.rootGroup(rootGroup)

        Then(s"we should ${if (expectFailure) "" else "NOT "}get an error")
        withClue(jsonResult(result)) { result.isFailure should be(expectFailure) }

        And("ExternalVolumes validation agrees")
        val externalVolumesResult = ExternalVolumes.validRootGroup()(rootGroup)
        withClue(jsonResult(externalVolumesResult)) { externalVolumesResult.isFailure should be(expectFailure) }

        And("global validation agrees")
        val globalResult: Result = RootGroup.validRootGroup(AllConf.withTestConfig("--enable_features", "external_volumes"))(rootGroup)
        withClue(jsonResult(globalResult)) { globalResult.isFailure should be(expectFailure) }

        val ruleViolations = globalResult match {
          case Success => Seq.empty[ConstraintViolation]
          case f: Failure => Validation.allViolations(f)
        }

        And("the rule violations are the expected ones")
        withClue(jsonResult(globalResult)) {
          ruleViolations.toSet should equal(expectedViolations)
        }
      }
    }
  }
}
