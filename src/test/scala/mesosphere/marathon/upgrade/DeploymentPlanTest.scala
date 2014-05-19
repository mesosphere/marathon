package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2.{ScalingStrategy, Group}
import org.scalatest.Matchers

class DeploymentPlanTest extends MarathonSpec with Matchers {

  test("start from empty group") {
    val app = AppDefinition("app")
    val from = Group("group", ScalingStrategy(1), Seq.empty)
    val to = Group("group", ScalingStrategy(1), Seq(app))
    val plan = DeploymentPlan("plan", from, to)

    plan.toStart should have size 1
    plan.toRestart should have size 0
    plan.toScale should have size 0
    plan.toStop should have size 0
  }

  test("start from running group") {
    val app = AppDefinition("app", "sleep 10")
    val appUpdate = AppDefinition("app", "sleep 30")
    val app2 = AppDefinition("app2", "cmd2")
    val app3 = AppDefinition("app3", "cmd3")

    val from = Group("group", ScalingStrategy(1), Seq(app, app2))
    val to = Group("group", ScalingStrategy(1), Seq(appUpdate, app2, app3))
    val plan = DeploymentPlan("plan", from, to)

    plan.toStart should have size 1
    plan.toRestart should have size 1
    plan.toScale should have size 0
    plan.toStop should have size 0
  }
}
