package mesosphere.marathon.upgrade

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.api.v2._
import org.scalatest.Matchers


class DeploymentPlanTest extends MarathonSpec with Matchers {

  val app1_1 = AppDefinition("app1", "/app1", instances = 10)
  val app1_2 = AppDefinition("app1", "/app2", instances = 100)
  val group1_1 = Group("g1", CanaryStrategy(Seq.empty[Step], 123), Seq(app1_1))
  val group1_2 = Group("g1", CanaryStrategy(Seq(RelativeStep(0.1), RelativeStep(0.3), RelativeStep(0.5), RelativeStep(1)), 123), Seq(app1_2))
  val currentlyRunning = Map("app1"->List("0","1","2","3","4","5","6","7","8","9"))

  test("Deployment plan generation from plan") {
    val plan = DeploymentPlan(group1_1, group1_2, currentlyRunning)
    plan.steps should have size 5
    val first = plan.steps.head
    val second = plan.steps(1)
    val last = plan.steps.last
    first.deployments.head.taskIdsToKill should be('empty)
    first.deployments.head.scaleUp should be(10)
    second.deployments.head.taskIdsToKill should be(List("0"))
    second.deployments.head.scaleUp should be(20)
    last.deployments.head.scaleUp should be(0)
  }

  test("plan can be iterated") {
    val plan = DeploymentPlan(group1_1, group1_2, currentlyRunning)
    plan.steps should have size 5
    val end =  Range(0, 4).foldRight(plan){(_, plan) => plan.next}
    end.steps should have size 1
    end.last should be('defined)
  }
}
