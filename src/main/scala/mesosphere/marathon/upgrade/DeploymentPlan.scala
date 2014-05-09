package mesosphere.marathon.upgrade

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.Group

sealed trait DeploymentAction {
  def appId:String
  def count:Int
}
case class UpScaleAction(appId:String, count:Int) extends DeploymentAction
case class DownScaleAction(appId:String, count:Int) extends DeploymentAction

case class DeploymentStep(deployments:List[DeploymentAction])

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
  def apply(oldProduct:Group, newProduct:Group) : DeploymentPlan = {

    val strategy = newProduct.scalingStrategy.steps.toList
    val ups = strategy.map{ step =>
      newProduct.apps.toList.map(app => UpScaleAction(app.id, step.count(app)))
    }
    val downs = newProduct.scalingStrategy.steps.toList.map { step =>
      oldProduct.apps.toList.map(app => DownScaleAction(app.id, step.count(app)))
    }
    val steps =
      DeploymentStep(ups.head) ::
      downs.zip(ups.tail).map{ case (d, u) =>  DeploymentStep(d ::: u) } :::
      List(DeploymentStep(downs.reverse.head))

    DeploymentPlan(oldProduct.apps.toList, newProduct.apps.toList, steps)
  }
}
