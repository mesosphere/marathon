package mesosphere.marathon
package raml

import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.AppDefinition

trait DeploymentPlanConversion extends ReadinessConversions {

  implicit val deploymentPlanWrites: Writes[core.deployment.DeploymentPlan, raml.DeploymentPlan] = Writes{ plan =>
    raml.DeploymentPlan(
      id = plan.id,
      steps = plan.steps.toRaml,
      version = plan.version.toOffsetDateTime
    )
  }

  implicit val deploymentActionWrites: Writes[core.deployment.DeploymentAction, raml.DeploymentAction] = Writes { action =>
    val (app, pod) = action.runSpec match {
      case _: AppDefinition => (Some(action.runSpec.id.toString), None)
      case _: PodDefinition => (None, Some(action.runSpec.id.toString))
    }
    raml.DeploymentAction(
      action = raml.DeploymentActionName.fromString(core.deployment.DeploymentAction.actionName(action)).get,
      app = app,
      pod = pod
    )
  }

  implicit val deploymentStepWrites: Writes[core.deployment.DeploymentStep, raml.DeploymentStep] = Writes { step =>
    raml.DeploymentStep(actions = step.actions.toRaml)
  }

  implicit val deploymentStepInfoWrites: Writes[core.deployment.DeploymentStepInfo, raml.DeploymentStepInfo] = Writes { info =>
    raml.DeploymentStepInfo(
      id = info.plan.id,
      version = info.plan.version.toOffsetDateTime,
      affectedApps = info.plan.affectedAppIds.map(_.toString).to(Seq),
      affectedPods = info.plan.affectedPodIds.map(_.toString).to(Seq),
      steps = info.plan.steps.toRaml,
      currentActions = info.step.actions.map(currentAction(info)),
      currentStep = info.stepIndex,
      totalSteps = info.plan.steps.size
    )
  }

  def currentAction(info: core.deployment.DeploymentStepInfo)(action: core.deployment.DeploymentAction): raml.DeploymentActionInfo = {
    val (app, pod) = action.runSpec match {
      case _: AppDefinition => (Some(action.runSpec.id.toString), None)
      case _: PodDefinition => (None, Some(action.runSpec.id.toString))
    }
    raml.DeploymentActionInfo(
      action = raml.DeploymentActionName.fromString(core.deployment.DeploymentAction.actionName(action)).get,
      app = app,
      pod = pod,
      readinessCheckResults = info.readinessChecksByApp(action.runSpec.id).toRaml
    )
  }
}
