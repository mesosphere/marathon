package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.{RollingStrategy, CanaryStrategy, ScalingStrategy, Group}
import mesosphere.marathon.state.{Timestamp, MarathonState}
import mesosphere.marathon.Protos.{DeploymentActionDefinition, DeploymentStepDefinition, DeploymentPlanDefinition}
import scala.collection.JavaConversions._
import scala.concurrent.Future
import mesosphere.marathon.MarathonSchedulerService
import scala.concurrent.ExecutionContext.Implicits.global

case class DeploymentAction(appId:String, scaleUp:Int, taskIdsToKill:List[String])

case class DeploymentStep(deployments:List[DeploymentAction], waitTime:Int)

case class DeploymentPlan(
  id: String,
  strategy: ScalingStrategy,
  original: List[AppDefinition],
  target: List[AppDefinition],
  steps: List[DeploymentStep],
  current: Option[DeploymentStep] = None,
  last: Option[DeploymentStep] = None,
  version : Timestamp = Timestamp.now()
) extends MarathonState[DeploymentPlanDefinition, DeploymentPlan] {

  def next : DeploymentPlan = steps match {
    case head :: rest => copy(steps=rest, current=Some(head), last=current)
    case Nil => throw new NoSuchElementException("empty plan")
  }

  def hasNext : Boolean = !steps.isEmpty

  def originalIds : Set[String] = original.map(_.id).toSet

  def targetIds : Set[String] = target.map(_.id).toSet

  override def mergeFromProto(bytes: Array[Byte]): DeploymentPlan = mergeFromProto(DeploymentPlanDefinition.parseFrom(bytes))

  override def mergeFromProto(msg: DeploymentPlanDefinition): DeploymentPlan = {
    def action(da:DeploymentActionDefinition) = DeploymentAction(da.getAppId, da.getScale, da.getTaksIdsToKillList.toList)
    def step(dd:DeploymentStepDefinition) = DeploymentStep(dd.getDeploymentsList.map(action).toList, dd.getWaitTime)
    DeploymentPlan(
      msg.getId,
      ScalingStrategy.fromProto(msg.getStrategy),
      msg.getOrigList.map(AppDefinition.fromProto).toList,
      msg.getTargetList.map(AppDefinition.fromProto).toList,
      msg.getStepsList.map(step).toList,
      if (msg.hasCurrent) Some(step(msg.getCurrent)) else None,
      if (msg.hasLast) Some(step(msg.getLast)) else None,
      Timestamp(msg.getVersion)
    )
  }

  override def toProto: DeploymentPlanDefinition = {
    def action(da:DeploymentAction) : DeploymentActionDefinition = DeploymentActionDefinition.newBuilder()
      .setAppId(da.appId).setScale(da.scaleUp).addAllTaksIdsToKill(da.taskIdsToKill).build()
    def step(dd:DeploymentStep) = DeploymentStepDefinition.newBuilder()
      .addAllDeployments(dd.deployments.map(action))
      .setWaitTime(dd.waitTime).build()
    val builder = DeploymentPlanDefinition.newBuilder()
      .setId(id)
      .setVersion(version.toString)
      .setStrategy(strategy.toProto)
      .addAllOrig(original.map(_.toProto))
      .addAllTarget(target.map(_.toProto))
      .addAllSteps(steps.map(step))
    current.map(step).foreach(builder.setCurrent)
    last.map(step).foreach(builder.setLast)
    builder.build()
  }

  def deploy(scheduler: MarathonSchedulerService) : Future[Boolean] = {
    def deployRolling(r: RollingStrategy) : Future[Boolean] = {
      val toRestart = targetIds.intersect(originalIds)
      val toStart = targetIds.filterNot(toRestart.contains)
      val toStop = originalIds.filterNot(toRestart.contains)
      val restartFuture = toRestart.flatMap(id => original.find(_.id==id)).map { app =>
        scheduler.upgradeApp(app, (app.instances * r.minimumHealthCapacity).toInt)
      }
      val startFuture = toStart.flatMap(id => target.find(_.id==id)).map(scheduler.startApp(_).map(_ => true))
      val stopFuture = toStop.flatMap(id => original.find(_.id==id)).map(scheduler.stopApp(_).map(_ => true))
      Future.sequence(restartFuture ++ startFuture ++ stopFuture).map(_.forall(identity))
    }
    strategy match {
      case r:RollingStrategy => deployRolling(r)
    }
  }
}

object DeploymentPlan {
  def apply(id:String, oldProduct:Group, newProduct:Group, runningTasks:Map[String, List[String]]) : DeploymentPlan = {
    val steps = newProduct.scalingStrategy match {
      case CanaryStrategy(canarySteps, watchPeriod) =>
        var takeDown = Map.empty[String, Int].withDefaultValue(0)
        var currentScale = Map.empty[String, Int].withDefaultValue(0)
        var taskTaken = Map.empty[String, List[String]].withDefaultValue(Nil)
        val firstSteps = canarySteps.toList.map { step =>
          //create actions for this step
          val actions = newProduct.apps.toList.map { app =>
            val count = takeDown(app.id)
            val toKill = runningTasks(app.id).take(count).filterNot(taskTaken(app.id).contains)
            val action = DeploymentAction(app.id, step.count(app) - currentScale(app.id), toKill)
            taskTaken += app.id -> (taskTaken(app.id) ::: action.taskIdsToKill)
            currentScale += app.id -> (currentScale(app.id) + action.scaleUp)
            action
          }
          //store take down actions for next step
          oldProduct.apps.foreach(app => takeDown += app.id -> step.count(app))
          DeploymentStep(actions, watchPeriod)
        }
        val lastStep = {
          val actions = oldProduct.apps.toList.map { app =>
            val finalCount = newProduct.apps.find(_.id == app.id).fold(0)(_.instances.toInt) - currentScale(app.id)
            DeploymentAction(app.id, finalCount, runningTasks(app.id).filterNot(taskTaken(app.id).contains))
          }
          DeploymentStep(actions, watchPeriod)
        }
        firstSteps ::: List(lastStep)
      case _ => Nil
    }
    DeploymentPlan(id, newProduct.scalingStrategy, oldProduct.apps.toList, newProduct.apps.toList, steps)
  }

  def empty() = DeploymentPlan("", RollingStrategy(1), Nil, Nil, Nil)
}
