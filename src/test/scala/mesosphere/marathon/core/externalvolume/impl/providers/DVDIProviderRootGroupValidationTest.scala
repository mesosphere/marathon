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
      val app1 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/foo1"), volumeName = "vol1")
      val app2 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/foo2"), volumeName = "vol2")
      val rootGroup = Builders.newRootGroup(apps = Seq(app1, app2))

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    "two volumes with same name result in an error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app1"), volumeName = "vol")
      val app2 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app2"), volumeName = "vol")
      val rootGroup = Builders.newRootGroup(apps = Seq(app1, app2))

      f.checkResult(
        rootGroup,
        expectedViolations = Set(
          ConstraintViolation(
            constraint = "Volume name 'vol' in /nested/app1 conflicts with volume(s) of same name in app(s): /nested/app2",
            path = "/groups(0)/apps(0)/externalVolumes(0)"
          ),
          ConstraintViolation(
            constraint = "Volume name 'vol' in /nested/app2 conflicts with volume(s) of same name in app(s): /nested/app1",
            path = "/groups(0)/apps(1)/externalVolumes(0)"
          )
        )
      )
    }

    "two volumes with same name and shared true result in no error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app1"), volumeName = "vol", shared = true)
      val app2 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app2"), volumeName = "vol", shared = true)
      val rootGroup = Builders.newRootGroup(apps = Seq(app1, app2))

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    "two volumes with same name and only one has shared true result in an error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app1"), volumeName = "vol", shared = true)
      val app2 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app2"), volumeName = "vol", shared = false)
      val rootGroup = Builders.newRootGroup(apps = Seq(app1, app2))

      f.checkResult(
        rootGroup,
        expectedViolations = Set(
          ConstraintViolation(
            constraint = "Volume name 'vol' in /nested/app1 conflicts with volume(s) of same name in app(s): /nested/app2",
            path = "/groups(0)/apps(0)/externalVolumes(0)"
          )
        )
      )
    }

    "a volume with parameters in the name and result in no error" in {
      val f = new Fixture
      Given("a root group with two apps and conflicting volumes")
      val app1 = f.appWithDVDIVolume(appId = AbsolutePathId("/nested/app1"), volumeName = "name=teamvolumename,secret_key=volume-secret-key-team,secure=true,size=5,repl=1,shared=true")
      val rootGroup = Builders.newRootGroup(apps = Seq(app1))

      f.checkResult(
        rootGroup,
        expectedViolations = Set.empty
      )
    }

    class Fixture {
      def appWithDVDIVolume(appId: AbsolutePathId, volumeName: String, provider: String = DVDIProvider.name, shared: Boolean = false): AppDefinition = {
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
                      shared = shared,
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
