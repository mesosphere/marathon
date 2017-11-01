package mesosphere.marathon
package api.akkahttp.v2

import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.scalalogging.StrictLogging
import mesosphere.UnitTest
import mesosphere.marathon.api.TestAuthFixture
import mesosphere.marathon.core.appinfo.GroupInfoService
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.test.GroupCreation
import org.scalatest.Inside

class GroupsControllerTest extends UnitTest with ScalatestRouteTest with Inside with RouteBehaviours with StrictLogging with GroupCreation {
  case class Fixture(authenticated: Boolean = true,
                     authorized: Boolean = true,
                     authFn: Any => Boolean = _ => true,
                     electionService: ElectionService = mock[ElectionService],
                     infoService: GroupInfoService = mock[GroupInfoService]) {
    val authFixture = new TestAuthFixture()
    authFixture.authenticated = authenticated
    authFixture.authorized = authorized
    authFixture.authFn = authFn

    implicit val authenticator = authFixture.auth

    val groupsController: GroupsController = new GroupsController(electionService, infoService)
  }
}
