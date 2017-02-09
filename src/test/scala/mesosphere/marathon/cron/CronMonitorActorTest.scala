package mesosphere.marathon.cron

import akka.testkit.{TestActorRef, TestProbe}
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.{SchedulerActions, Seq}
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.{DeploymentStatus, InstanceChanged, InstanceHealthChanged}
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launcher.impl.LaunchQueueTestHelper
import mesosphere.marathon.core.launchqueue.LaunchQueue
import mesosphere.marathon.core.leadership.AlwaysElectedLeadershipModule
import mesosphere.marathon.core.readiness.ReadinessCheckExecutor
import mesosphere.marathon.core.task.tracker.{InstanceCreationHandler, InstanceTracker}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, RootGroup}
import mesosphere.marathon.test.{GroupCreation, MarathonTestHelper}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.schedule._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.GroupCreation
import org.mockito.Mockito.{spy, when}

import scala.concurrent.Future
import scala.concurrent.duration._

class CronMonitorActorTest extends AkkaUnitTest with GroupCreation {

  "CronMonitorActor" should {

    for (
      (counts, description) <- Seq(
        None -> "with no item in queue",
        Some(LaunchQueueTestHelper.zeroCounts) -> "with zero count queue item"
      )
    ) {

      val app1 = AppDefinition(
        id = "/test/group2/app1".toPath,
        schedule = Schedule(
          SchedulingStrategy(
            Periodic("nonsense"),
            CancelingBehavior(1, 3 minutes, 3 hours)
          )
        )
      )

      val app2 = AppDefinition(
        id = "/test/group2/app2".toPath,
        schedule = Continuous
      )

      val mockRootGroup: RootGroup = createRootGroup(
        groups = Set(
          createGroup("/test".toPath, groups = Set(
            createGroup("/test/group1".toPath, Map(app1.id -> app1)),
            createGroup("/test/group2".toPath, Map(app2.id -> app2))
          ))))

        "be able to periodically launch tasks" in {

        val f = new Fixture

        val cronMonitorActor = system actorOf {
          CronMonitorActor.props(
            _ => f.scheduler,
            f.launchQueue,
            2 seconds // Short period for fast testing
          )
        }

        Future.successful()


        when(f.scheduler.groupRepository.root()).thenReturn {
          Future.success(mockRootGroup)
        }

          //TODO:  Spy root() call after 4 seconds and check it has been called.

      }

    }

    "be able to get takjs from the GroupRepository" in {

      val f = new Fixture
      //when(f.scheduler.groupRepository.root())

    }

  }

  class Fixture {
    
    val scheduler: SchedulerActions = mock[SchedulerActions]
    val launchQueue: LaunchQueue = mock[LaunchQueue]

  }

}
