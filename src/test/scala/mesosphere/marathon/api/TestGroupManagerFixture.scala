package mesosphere.marathon
package api

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import java.util.concurrent.atomic.AtomicInteger

import javax.inject.Provider
import akka.event.EventStream
import mesosphere.marathon.core.group.GroupManagerModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.RootGroup
import mesosphere.marathon.storage.repository.{AppRepository, GroupRepository, PodRepository}
import mesosphere.marathon.test.Mockito

import scala.concurrent.{ExecutionContext, Future}

class TestGroupManagerFixture(
    initialRoot: RootGroup = RootGroup.empty,
    authenticated: Boolean = true,
    authorized: Boolean = true,
    authFn: Any => Boolean = _ => true)(implicit as: ActorSystem, ec: ExecutionContext) extends Mockito {
  implicit val mat = ActorMaterializer()
  val service = mock[MarathonSchedulerService]
  val metrics = DummyMetrics
  val store = new InMemoryPersistenceStore(metrics)
  store.markOpen()

  val maxVersionsCacheSize = 1000

  val appRepository = AppRepository.inMemRepository(store)
  val podRepository = PodRepository.inMemRepository(store)
  val groupRepository = GroupRepository.inMemRepository(store, appRepository, podRepository, maxVersionsCacheSize)
  groupRepository.storeRoot(initialRoot, Nil, Nil, Nil, Nil)
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

  schedulerProvider.get().listRunningDeployments() returns Future.successful(Seq.empty)

  private[this] val groupManagerModule = new GroupManagerModule(
    metrics = metrics,
    config = config,
    scheduler = schedulerProvider,
    groupRepo = groupRepository)(ExecutionContext.Implicits.global, eventBus, authenticator)

  val groupManager = groupManagerModule.groupManager
}
