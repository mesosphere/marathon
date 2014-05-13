package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.Group
import scala.concurrent.Future

case class DeploymentAction(appId:String, scale:Int, taskIdsToKill:List[String])

case class DeploymentStep(deployments:List[DeploymentAction], waitTime:Int, isValid: () => Future[Boolean] )

case class DeploymentPlan(
  orig:List[AppDefinition],
  target:List[AppDefinition],
  steps:List[DeploymentStep],
  current:Option[DeploymentStep] = None,
  last:Option[DeploymentStep] = None
) {

  def next : DeploymentPlan = steps match {
    case head :: rest => DeploymentPlan(orig, target, rest, Some(head), current)
    case Nil => throw new NoSuchElementException("empty plan")
  }

  def hasNext : Boolean = !steps.isEmpty
}

object DeploymentPlan {
  def apply(oldProduct:Group, newProduct:Group, runningTasks:Map[String, List[String]]) : DeploymentPlan = {
    var takeDown = Map.empty[String, Int].withDefaultValue(0)
    val steps = newProduct.scalingStrategy.steps.toList.map { step =>
      //create actions for this step
      val actions = newProduct.apps.toList.map { app =>
        val count = takeDown(app.id)
        val toKill = runningTasks(app.id).take(count)
        DeploymentAction(app.id, step.count(app), toKill)
      }
      //store take down actions for next step
      oldProduct.apps.foreach( app => takeDown += app.id->step.count(app))
      DeploymentStep(actions, newProduct.scalingStrategy.watchPeriod, () => Future.successful(true))
    }

    val lastStep = {
      val actions = oldProduct.apps.toList.map { app =>
        val finalCount = newProduct.apps.find(_.id == app.id).fold(0)(_.instances.toInt)
        DeploymentAction(app.id, finalCount, runningTasks(app.id))
      }
      DeploymentStep(actions, newProduct.scalingStrategy.watchPeriod, () => Future.successful(true))
    }

    DeploymentPlan(oldProduct.apps.toList, newProduct.apps.toList, steps ::: List(lastStep))
  }
}
