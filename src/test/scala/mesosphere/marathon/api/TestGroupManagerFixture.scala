package mesosphere.marathon
package api

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

import akka.event.EventStream
import mesosphere.AkkaUnitTestLike
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.Mockito

import scala.concurrent.Future
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.AkkaUnitTestLike

class TestGroupManagerFixture(
    initialRoot: RootGroup = RootGroup.empty,
    authenticated: Boolean = true,
    authorized: Boolean = true,
    authFn: Any => Boolean = _ => true) extends Mockito with AkkaUnitTestLike {
  val service = mock[MarathonSchedulerService]
  val groupRepository = mock[GroupRepository]
  val eventBus = mock[EventStream]

  val authFixture = new TestAuthFixture()
  authFixture.authenticated = authenticated
  authFixture.authorized = authorized
  authFixture.authFn = authFn

  implicit val authenticator = authFixture.auth

  val config = AllConf.withTestConfig("--zk_timeout", "3000")

  val actorId = new AtomicInteger(0)

  val schedulerProvider = new Provider[DeploymentService] {
    override def get() = service
  }

  groupRepository.root() returns Future.successful(initialRoot)

  private[this] val groupManagerModule = new GroupManagerModule(
    config = config,
    scheduler = schedulerProvider,
    groupRepo = groupRepository)(ExecutionContexts.global, eventBus, authenticator)

  val groupManager = groupManagerModule.groupManager
}
