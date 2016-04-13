package mesosphere.marathon.core.externalvolume.impl.providers

import com.wix.accord.{ RuleViolation, Success, Failure, Result }
import mesosphere.marathon.AllConf
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.externalvolume.ExternalVolumes
import mesosphere.marathon.state._
import org.apache.mesos.{ Protos => MesosProtos }
import org.scalatest.{ FunSuite, GivenWhenThen, Matchers }
import play.api.libs.json.{ JsString, Json }

import scala.collection.immutable.Seq

class DVDIProviderRootGroupValidationTest extends FunSuite with Matchers with GivenWhenThen {

  test("two volumes with different names should not result in an error") {
    val f = new Fixture
    Given("a root group with two apps and conflicting volumes")
    val rootGroup = Group(
      id = PathId.empty,
      groups = Set(
        Group(
          id = PathId("/nested"),
          apps = Set(
            f.appWithDVDIVolume(appId = PathId("/nested/foo1"), volumeName = "vol1"),
            f.appWithDVDIVolume(appId = PathId("/nested/foo2"), volumeName = "vol2")
          )
        )
      )
    )

    f.checkResult(
      rootGroup,
      expectedViolations = Set.empty
    )
  }

  test("two volumes with same name result in an error") {
    val f = new Fixture
    Given("a root group with two apps and conflicting volumes")
    val app1 = f.appWithDVDIVolume(appId = PathId("/nested/app1"), volumeName = "vol")
    val app2 = f.appWithDVDIVolume(appId = PathId("/nested/app2"), volumeName = "vol")
    val rootGroup = Group(
      id = PathId.empty,
      groups = Set(
        Group(
          id = PathId("/nested"),
          apps = Set(app1, app2)
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

    //enable feature external volumes
    AllConf.withTestConfig(Seq("--enable_features", "external_volumes"))

    def appWithDVDIVolume(appId: PathId, volumeName: String, provider: String = DVDIProvider.name): AppDefinition = {
      AppDefinition(
        id = appId,
        cmd = Some("sleep 123"),
        upgradeStrategy = UpgradeStrategy.forResidentTasks,
        container = Some(
          Container(
            `type` = MesosProtos.ContainerInfo.Type.MESOS,
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
          case Success    => JsString("Success")
          case f: Failure => Json.toJson(f)(Validation.failureWrites)
        }
      )
    }

    def checkResult(rootGroup: Group, expectedViolations: Set[RuleViolation]): Unit = {
      val expectFailure = expectedViolations.nonEmpty

      When("validating the root group")
      val result = DVDIProviderValidations.rootGroup(rootGroup)

      Then(s"we should ${if (expectFailure) "" else "NOT "}get an error")
      withClue(jsonResult(result)) { result.isFailure should be(expectFailure) }

      And("ExternalVolumes validation agrees")
      val externalVolumesResult = ExternalVolumes.validRootGroup()(rootGroup)
      withClue(jsonResult(externalVolumesResult)) { externalVolumesResult.isFailure should be(expectFailure) }

      And("global validation agrees")
      val globalResult: Result = Group.validRootGroup(maxApps = None)(rootGroup)
      withClue(jsonResult(globalResult)) { globalResult.isFailure should be(expectFailure) }

      val ruleViolations = globalResult match {
        case Success    => Set.empty[RuleViolation]
        case f: Failure => f.violations.flatMap(Validation.allRuleViolationsWithFullDescription(_))
      }

      And("the rule violations are the expected ones")
      withClue(jsonResult(globalResult)) {
        ruleViolations should equal(expectedViolations)
      }
    }
  }
}
