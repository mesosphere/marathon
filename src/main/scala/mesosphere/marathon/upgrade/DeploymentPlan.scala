package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future
import mesosphere.marathon.state.MarathonState
import mesosphere.marathon.Protos.{DeploymentActionDefinition, DeploymentStepDefinition, DeploymentPlanDefinition}
import scala.collection.JavaConversions._

case class DeploymentAction(appId:String, scale:Int, taskIdsToKill:List[String])

case class DeploymentStep(deployments:List[DeploymentAction], waitTime:Int)

case class DeploymentPlan(
  orig:List[AppDefinition],
  target:List[AppDefinition],
  steps:List[DeploymentStep],
  current:Option[DeploymentStep] = None,
  last:Option[DeploymentStep] = None
) extends MarathonState[DeploymentPlanDefinition, DeploymentPlan] {

  def next : DeploymentPlan = steps match {
    case head :: rest => DeploymentPlan(orig, target, rest, Some(head), current)
    case Nil => throw new NoSuchElementException("empty plan")
  }

  def hasNext : Boolean = !steps.isEmpty

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan = mergeFromProto(DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: DeploymentPlanDefinition): DeploymentPlan = {
    def action(da:DeploymentActionDefinition) = DeploymentAction(da.getAppId, da.getScale, da.getTaksIdsToKillList.toList)
    def step(dd:DeploymentStepDefinition) = DeploymentStep(dd.getDeploymentsList.map(action).toList, dd.getWaitTime)
    DeploymentPlan(
      msg.getOrigList.map(AppDefinition.fromProto).toList,
      msg.getTargetList.map(AppDefinition.fromProto).toList,
      msg.getStepsList.map(step).toList,
      if (msg.hasCurrent) Some(step(msg.getCurrent)) else None,
      if (msg.hasLast) Some(step(msg.getLast)) else None
    )
  }

  override def toProto: DeploymentPlanDefinition = {
    def action(da:DeploymentAction) : DeploymentActionDefinition = ???
    def step(dd:DeploymentStep) = DeploymentStepDefinition.newBuilder()
      .addAllDeployments(dd.deployments.map(action))
      .setWaitTime(dd.waitTime).build()
    val builder = DeploymentPlanDefinition.newBuilder()
      .addAllOrig(orig.map(_.toProto))
      .addAllTarget(target.map(_.toProto))
      .addAllSteps(steps.map(step))
    current.map(step).foreach(builder.setCurrent)
    last.map(step).foreach(builder.setLast)
    builder.build()
  }
}

object DeploymentPlan {
  def apply(oldProduct:Group, newProduct:Group, runningTasks:Map[String, List[String]]) : DeploymentPlan = {
    var takeDown = Map.empty[String, Int].withDefaultValue(0)
    var currentScale = Map.empty[String, Int].withDefaultValue(0)
    var taskTaken = Map.empty[String, List[String]].withDefaultValue(Nil)
    val steps = newProduct.scalingStrategy.steps.toList.map { step =>
      //create actions for this step
      val actions = newProduct.apps.toList.map { app =>
        val count = takeDown(app.id)
        val toKill = runningTasks(app.id).take(count).filterNot(taskTaken(app.id).contains)
        val action = DeploymentAction(app.id, step.count(app) - currentScale(app.id), toKill)
        taskTaken += app.id -> (taskTaken(app.id) ::: action.taskIdsToKill)
        currentScale += app.id -> (currentScale(app.id) + action.scale)
        action
      }
      //store take down actions for next step
      oldProduct.apps.foreach( app => takeDown += app.id->step.count(app))
      DeploymentStep(actions, newProduct.scalingStrategy.watchPeriod)
    }

    val lastStep = {
      val actions = oldProduct.apps.toList.map { app =>
        val finalCount = newProduct.apps.find(_.id == app.id).fold(0)(_.instances.toInt) - currentScale(app.id)
        DeploymentAction(app.id, finalCount, runningTasks(app.id).filterNot(taskTaken(app.id).contains))
      }
      DeploymentStep(actions, newProduct.scalingStrategy.watchPeriod)
    }

    DeploymentPlan(oldProduct.apps.toList, newProduct.apps.toList, steps ::: List(lastStep))
  }

  def empty() = DeploymentPlan(Nil, Nil, Nil)
}
