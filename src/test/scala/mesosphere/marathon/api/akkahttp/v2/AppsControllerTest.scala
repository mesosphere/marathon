package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.testkit.ScalatestRouteTest
import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.appinfo.AppInfoService
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.test.SettableClock

class AppsControllerTest extends UnitTest with ScalatestRouteTest {

  case class Fixture(
                      clock: SettableClock = new SettableClock(),
                      auth: TestAuthFixture = new TestAuthFixture(),
                      appInfoService: AppInfoService = mock[AppInfoService],
                      configArgs: Seq[String] = Seq("--enable_features", "external_volumes"),
                      groupManager: GroupManager = mock[GroupManager]) {
    val config: AllConf = AllConf.withTestConfig(configArgs: _*)

    implicit val authenticator = auth.auth
    implicit val authorizer = auth.auth

    implicit val electionService: ElectionService = mock[ElectionService]

    when(electionService.isLeader).thenReturn(true)

    val appsController = new AppsController(
      clock = clock,
      eventBus = system.eventStream,
      appInfoService = appInfoService,
      config = config,
      groupManager = groupManager,
      pluginManager = PluginManager.None
    )
  }
}
