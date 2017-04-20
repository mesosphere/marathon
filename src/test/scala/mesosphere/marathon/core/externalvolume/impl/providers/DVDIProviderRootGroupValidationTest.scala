package mesosphere.marathon
package core.externalvolume.impl.providers

import com.wix.accord.{ Failure, Result, RuleViolation, Success }
import mesosphere.UnitTest
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.state._
import mesosphere.marathon.test.GroupCreation
import org.apache.mesos.{ Protos => MesosProtos }
import play.api.libs.json.{ JsString, Json }

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
            id = PathId("/nested"),
            apps = Map(
              app1.id -> app1,
              app2.id -> app2
            )
          )
        )
      )

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    "two volumes with same name result in an error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = PathId("/nested/app1"), volumeName = "vol")
      val app2 = f.appWithDVDIVolume(appId = PathId("/nested/app2"), volumeName = "vol")
      val rootGroup = createRootGroup(
        groups = Set(
          createGroup(
            id = PathId("/nested"),
            apps = Map(
              app1.id -> app1,
              app2.id -> app2
            )
          )
        )
      )

      f.checkResult(
        rootGroup,
        expectedViolations = Set(
          RuleViolation(
            value = app1.externalVolumes.head,
            constraint = "Volume name 'vol' in /nested/app1 conflicts with volume(s) of same name in app(s): /nested/app2",
            description = Some("/groups(0)/apps(0)/externalVolumes(0)")
          ),
          RuleViolation(
            value = app2.externalVolumes.head,
            constraint = "Volume name 'vol' in /nested/app2 conflicts with volume(s) of same name in app(s): /nested/app1",
            description = Some("/groups(0)/apps(1)/externalVolumes(0)")
          )
        )
      )
    }

    class Fixture {
      def appWithDVDIVolume(appId: PathId, volumeName: String, provider: String = DVDIProvider.name): AppDefinition = {
        AppDefinition(
          id = appId,
          cmd = Some("sleep 123"),
          upgradeStrategy = UpgradeStrategy.forResidentTasks,
          container = Some(
            Container.Mesos(
              volumes = Seq(
                ExternalVolume(
                  containerPath = "ignoreme",
                  external = ExternalVolumeInfo(
                    name = volumeName,
                    provider = provider,
                    options = Map(
                      DVDIProvider.driverOption -> "rexray"
                    )
                  ),
                  mode = MesosProtos.Volume.Mode.RW
                )
              )
            )
          )
        )
      }

      def jsonResult(result: Result): String = {
        Json.prettyPrint(
          result match {
            case Success => JsString("Success")
            case f: Failure => Json.toJson(f)(Validation.failureWrites)
          }
        )
      }

      def checkResult(rootGroup: RootGroup, expectedViolations: Set[RuleViolation]): Unit = {
        val expectFailure = expectedViolations.nonEmpty

        When("validating the root group")
        val result = DVDIProviderValidations.rootGroup(rootGroup)

        Then(s"we should ${if (expectFailure) "" else "NOT "}get an error")
        withClue(jsonResult(result)) { result.isFailure should be(expectFailure) }

        And("ExternalVolumes validation agrees")
        val externalVolumesResult = ExternalVolumes.validRootGroup()(rootGroup)
        withClue(jsonResult(externalVolumesResult)) { externalVolumesResult.isFailure should be(expectFailure) }

        And("global validation agrees")
        val globalResult: Result = RootGroup.rootGroupValidator(Set("external_volumes"))(rootGroup)
        withClue(jsonResult(globalResult)) { globalResult.isFailure should be(expectFailure) }

        val ruleViolations = globalResult match {
          case Success => Set.empty[RuleViolation]
          case f: Failure => f.violations.flatMap(Validation.allRuleViolationsWithFullDescription(_))
        }

        And("the rule violations are the expected ones")
        withClue(jsonResult(globalResult)) {
          ruleViolations should equal(expectedViolations)
        }
      }
    }
  }
}
