package mesosphere.marathon
package core.task.tracker

import akka.Done
import akka.actor.ActorSystem
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.update.InstanceChangedEventsGenerator
import mesosphere.marathon.core.task.tracker.InstanceTracker.InstancesBySpec
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.test.Mockito
import org.mockito.Matchers
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{ExecutionContext, Future}

trait InstanceTrackerFixture extends Mockito with ScalaFutures {
  def actorSystem: ActorSystem

  class InstanceStubbingException(message: String) extends RuntimeException(message)

  import scala.collection.mutable
  val goalChangeMap: mutable.Map[Instance.Id, Goal] = mutable.Map.empty[Instance.Id, Goal]
  val instanceTracker: InstanceTracker = mock[InstanceTracker]
  instanceTracker.setGoal(any, any) answers { args =>
    val instanceId = args(0).asInstanceOf[Instance.Id]
    val maybeInstance = instanceTracker.get(instanceId).futureValue
    maybeInstance.map { instance =>
      val goal = args(1).asInstanceOf[Goal]
      goalChangeMap.update(instanceId, goal)
      sendKilled(instance)
      Future.successful(Done)
    }.getOrElse {
      Future.failed(throw new InstanceStubbingException(s"instance $instanceId is not stubbed"))
    }
  }

  def populateInstances(instances: Seq[Instance]): Unit = {
    instances.foreach { instance =>
      instanceTracker.get(instance.instanceId) returns Future.successful(Some(instance))
    }
    instances.groupBy(_.runSpecId).foreach {
      case (path, instancesForPath) =>
        instanceTracker.specInstances(Matchers.eq(path))(any[ExecutionContext]) returns Future.successful(instancesForPath)
        instanceTracker.specInstancesSync(path) returns instancesForPath
        instanceTracker.list(Matchers.eq(path))(any[ExecutionContext]) returns Future.successful(instancesForPath)
    }
    instanceTracker.instancesBySpec()(any[ExecutionContext]) returns Future.successful(InstancesBySpec.forInstances(instances: _*))
  }

  private[this] def sendKilled(instance: Instance): Unit = {
    val updatedInstance = instance.copy(state = instance.state.copy(condition = Condition.Killed))
    val events = InstanceChangedEventsGenerator.events(updatedInstance, task = None, now = Timestamp(0), previousCondition = Some(instance.state.condition))
    events.foreach(actorSystem.eventStream.publish)
  }
}
