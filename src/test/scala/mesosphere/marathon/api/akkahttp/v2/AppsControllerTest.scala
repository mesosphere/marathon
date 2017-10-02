package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.model.{ HttpEntity, StatusCodes, Uri }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.{ JsonTestHelper, TestAuthFixture }
import mesosphere.marathon.core.appinfo.{ AppInfo, AppInfoService }
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.state.{ AppDefinition, VersionInfo }
import mesosphere.marathon.test.SettableClock

import scala.concurrent.Future

class AppsControllerTest extends UnitTest with ScalatestRouteTest {

  case class Fixture(
      clock: SettableClock = new SettableClock(),
      auth: TestAuthFixture = new TestAuthFixture(),
      service: MarathonSchedulerService = mock[MarathonSchedulerService],
      appInfoService: AppInfoService = mock[AppInfoService],
      configArgs: Seq[String] = Seq("--enable_features", "external_volumes"),
      groupManager: GroupManager = mock[GroupManager]) {
    val config: AllConf = AllConf.withTestConfig(configArgs: _*)

    implicit val authenticator = auth.auth

    implicit val electionService: ElectionService = mock[ElectionService]

    electionService.isLeader returns true

    val appsController = new AppsController(
      clock = clock,
      eventBus = system.eventStream,
      service = service,
      appInfoService = appInfoService,
      config = config,
      groupManager = groupManager,
      pluginManager = PluginManager.None
    )
  }

  "accessing an app with authentication returns an app" in {
    import mesosphere.marathon.state.PathId._

    val f = new Fixture()
    val appD = AppDefinition(id = "/a".toRootPath, versionInfo = VersionInfo.OnlyVersion(f.clock.now()))
    f.appInfoService.selectApp(any, any, any) returns Future.successful(Some(AppInfo(appD)))

    When("we try to fetch an app")
    Get(Uri./.withPath(Uri.Path("/a")), HttpEntity.Empty) ~> f.appsController.route ~> check {
      Then("we receive a response with the app info")
      status should be(StatusCodes.OK)
      val expected =
        """
          |{
          |  "app" : {
          |    "id" : "/a",
          |    "backoffFactor" : 1.15,
          |    "backoffSeconds" : 1,
          |    "cpus" : 1,
          |    "disk" : 0,
          |    "executor" : "",
          |    "instances" : 1,
          |    "labels" : { },
          |    "maxLaunchDelaySeconds" : 3600,
          |    "mem" : 128,
          |    "gpus" : 0,
          |    "networks" : [ {
          |      "mode" : "host"
          |    } ],
          |    "portDefinitions" : [ ],
          |    "requirePorts" : false,
          |    "upgradeStrategy" : {
          |      "maximumOverCapacity" : 1,
          |      "minimumHealthCapacity" : 1
          |    },
          |    "version" : "2015-04-09T12:30:00Z",
          |    "killSelection" : "YOUNGEST_FIRST",
          |    "unreachableStrategy" : {
          |      "inactiveAfterSeconds" : 0,
          |      "expungeAfterSeconds" : 0
          |    }
          |  }
          |}
        """.stripMargin
      JsonTestHelper.assertThatJsonString(responseAs[String]).correspondsToJsonString(expected)
    }
  }
}
