package mesosphere.marathon
package core.health.impl

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import mesosphere.marathon.core.health.{ Health, HealthCheck, MarathonHttpHealthCheck, PortReference }
import mesosphere.marathon.core.instance.TestInstanceBuilder
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.termination.{ KillReason, KillService }
import mesosphere.marathon.core.task.tracker.InstanceTracker
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.storage.repository.AppRepository
import mesosphere.marathon.test.{ MarathonActorSupport, MarathonSpec }
import mesosphere.util.CallerThreadExecutionContext
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ verify, verifyNoMoreInteractions, when }
import org.scalatest.{ BeforeAndAfter, Matchers }

import scala.concurrent.Future

class HealthCheckActorTest
    extends MarathonActorSupport
    with MarathonSpec with Matchers with BeforeAndAfter {

  override lazy implicit val system: ActorSystem =
    ActorSystem(
      name = "system",
      defaultExecutionContext = Some(CallerThreadExecutionContext.callerThreadExecutionContext)
    )

  // regression test for #934
  test("should not dispatch health checks for staging tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]

    when(appRepository.getVersion(appId, appVersion.toOffsetDateTime)).thenReturn(Future.successful(Some(app)))

    when(f.tracker.specInstancesSync(f.appId)).thenReturn(Seq(f.instance))

    val actor = f.actorWithLatch(latch)
    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  test("should not dispatch health checks for lost tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    when(f.tracker.specInstancesSync(f.appId)).thenReturn(Seq(f.unreachableInstance))

    val actor = f.actorWithLatch(latch)

    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  test("should not dispatch health checks for unreachable tasks") {
    val f = new Fixture
    val latch = TestLatch(1)
    when(f.tracker.specInstancesSync(f.appId)).thenReturn(Seq(f.unreachableInstance))

    val actor = f.actorWithLatch(latch)

    actor.underlyingActor.dispatchJobs()
    latch.isOpen should be (false)
    verifyNoMoreInteractions(f.driver)
  }

  // regression test for #1456
  test("task should be killed if health check fails") {
    val f = new Fixture
    val actor = f.actor(MarathonHttpHealthCheck(maxConsecutiveFailures = 3, portIndex = Some(PortReference(0))))

    actor.underlyingActor.checkConsecutiveFailures(f.instance, Health(f.instance.instanceId, consecutiveFailures = 3))
    verify(f.killService).killInstance(f.instance, KillReason.FailedHealthChecks)
    verifyNoMoreInteractions(f.tracker, f.driver, f.scheduler)
  }

  test("task should not be killed if health check fails, but the task is unreachable") {
    val f = new Fixture
    val actor = f.actor(MarathonHttpHealthCheck(maxConsecutiveFailures = 3, portIndex = Some(PortReference(0))))

    actor.underlyingActor.checkConsecutiveFailures(f.unreachableInstance, Health(f.unreachableInstance.instanceId, consecutiveFailures = 3))
    verifyNoMoreInteractions(f.tracker, f.driver, f.scheduler)
  }

  class Fixture {
    val tracker = mock[InstanceTracker]

    val appId = "/test".toPath
    val appVersion = Timestamp(1)
    val app = AppDefinition(id = appId)
    val appRepository: AppRepository = mock[AppRepository]
    val holder: MarathonSchedulerDriverHolder = new MarathonSchedulerDriverHolder
    val driver = mock[SchedulerDriver]
    holder.driver = Some(driver)
    when(appRepository.getVersion(appId, appVersion.toOffsetDateTime)).thenReturn(Future.successful(Some(app)))
    val killService: KillService = mock[KillService]
    when(appRepository.getVersion(appId, appVersion.toOffsetDateTime)).thenReturn(Future.successful(Some(app)))

    val scheduler: MarathonScheduler = mock[MarathonScheduler]

    import scala.concurrent.duration._

    val instanceBuilder = TestInstanceBuilder.newBuilder(appId, version = appVersion).addTaskRunning()

    private def createHackInstance() = {
      // TODO PODs remove magic when HealthCheckActor works on Instances -> just use instanceBuilder.getInstance()
      val tmpInstance = instanceBuilder.getInstance()
      tmpInstance.copy(state = tmpInstance.state.copy(since = tmpInstance.state.since - 1.second))
    }
    val instance = createHackInstance()

    val task: Task = instanceBuilder.pickFirstTask()

    val unreachableInstanceBuilder = TestInstanceBuilder.newBuilder(appId).addTaskUnreachable()
    val unreachableInstance = unreachableInstanceBuilder.getInstance()
    val unreachableTask: Task = unreachableInstanceBuilder.pickFirstTask()

    def actor(healthCheck: HealthCheck) = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(app, killService, healthCheck, tracker, system.eventStream)
      )
    )

    def actorWithLatch(latch: TestLatch) = TestActorRef[HealthCheckActor](
      Props(
        new HealthCheckActor(
          app,
          killService,
          MarathonHttpHealthCheck(portIndex = Some(PortReference(0))),
          tracker,
          system.eventStream) {

          override val workerProps = Props {
            latch.countDown()
            new TestActors.EchoActor
          }
        }
      )
    )
  }
}

