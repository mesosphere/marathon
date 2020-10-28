package mesosphere.marathon
package core.launchqueue.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.instance.update.{InstancesSnapshot}
import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}
import mesosphere.marathon.core.launchqueue.impl.OfferConstraints._

import mesosphere.mesos.protos.TextAttribute
import mesosphere.mesos.protos.Implicits._

class OfferConstraintsTest extends UnitTest {
  "OfferConstraints.State" should {
    "translate LIKE and UNLIKE into TextMatches/TextNotMatches" in {
      Given("an app with constraints 'foo:LIKE:bar,foo:UNLIKE:baz'")
      val like = Protos.Constraint.newBuilder
        .setField("foo")
        .setOperator(Protos.Constraint.Operator.LIKE)
        .setValue("bar")
        .build

      val unlike = Protos.Constraint.newBuilder
        .setField("foo")
        .setOperator(Protos.Constraint.Operator.UNLIKE)
        .setValue("baz")
        .build

      val runSpec = AppDefinition(id = AbsolutePathId("/app"), constraints = Set(like, unlike), role = "role")

      When("a scheduled instance is added")
      val state = OfferConstraints.State.empty.withInstanceAddedOrUpdated(Instance.scheduled(runSpec))

      Then("should produce a constraints group containing 'foo TextMatches bar' and 'foo TextNotMatches baz'")
      state.roleState shouldBe RoleConstraintState(
        Map(
          "role" -> Set(
            ConstraintGroup(Set(AttributeConstraint("foo", TextMatches("bar")), AttributeConstraint("foo", TextNotMatches("baz"))))
          )
        )
      )
    }

    "not produce any constraints for a role with an unconstrained app that has a scheduled instance" in {
      Given("two apps, the first one with 'foo:IS:bar' constraint, the second one unconstrained")
      val constraint = Protos.Constraint.newBuilder
        .setField("foo")
        .setOperator(Protos.Constraint.Operator.IS)
        .setValue("bar")
        .build

      val runSpec1 = AppDefinition(id = AbsolutePathId("/app/1"), constraints = Set(constraint), role = "role")
      val runSpec2 = AppDefinition(id = AbsolutePathId("/app/2"), constraints = Set(), role = "role")
      val scheduled1 = Instance.scheduled(runSpec1)
      val scheduled2 = Instance.scheduled(runSpec2)

      When("scheduled instances are added for both apps")
      val initialState = OfferConstraints.State.empty
        .withInstanceAddedOrUpdated(scheduled1)
        .withInstanceAddedOrUpdated(scheduled2)

      Then("no constraints should be produced")
      initialState.roleState shouldBe RoleConstraintState.empty

      When("the instance running update for an unconstrained app is received")
      val state = initialState.withInstanceAddedOrUpdated(TestInstanceBuilder(scheduled2).addTaskRunning().getInstance())

      Then("a 'foo TextEquals bar constraint' is produced")
      state.roleState shouldBe RoleConstraintState(Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("foo", TextEquals("bar")))))))
    }

    "correctly handle instance state updates for an app with a MAX_PER constraint" in {
      Given("an agent with an attribute 'foo:bar' and an app with a 'foo:MAX_PER:2' constraint and 3 instances")
      val agent = Instance.AgentInfo("agent", None, None, None, Seq(TextAttribute("foo", "bar")))

      val constraint = Protos.Constraint.newBuilder
        .setField("foo")
        .setOperator(Protos.Constraint.Operator.MAX_PER)
        .setValue("2")
        .build

      val runSpec = AppDefinition(id = AbsolutePathId("/app"), constraints = Set(constraint), role = "role")

      val scheduled1 = Instance.scheduled(runSpec)
      val scheduled2 = Instance.scheduled(runSpec)
      val scheduled3 = Instance.scheduled(runSpec)

      def running(scheduled: Instance): Instance =
        TestInstanceBuilder(scheduled).withAgentInfo(agent).addTaskRunning().getInstance()

      When("created from an instance snapshot with a scheduled instances 1, 2 and 3")
      val state0 = OfferConstraints.State.empty.withSnapshot(InstancesSnapshot(Seq(scheduled1, scheduled2, scheduled3)))

      Then("should result in a single 'foo Exists' constraint")
      state0.roleState shouldBe RoleConstraintState(Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("foo", Exists()))))))

      When("presented with the instance 1 running on an agent with an attribute 'foo:bar'")
      val state1 = state0.withInstanceAddedOrUpdated(running(scheduled1))

      Then("should still result in a single 'foo Exists' constraint")
      state1.roleState shouldBe RoleConstraintState(Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("foo", Exists()))))))

      When("presented with the instance 2 running on an agent with an attribute 'foo:bar'")
      val state2 = state1.withInstanceAddedOrUpdated(running(scheduled2))

      Then("should add a 'foo TextNotEquals bar' constraint")
      // NOTE: In presence of `foo TextNotEquals`, `foo Exists` is redundant; in principle, we could avoid emitting it.
      state2.roleState shouldBe RoleConstraintState(
        Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("foo", Exists()), AttributeConstraint("foo", TextNotEquals("bar"))))))
      )

      When("presented with deletion of the instance 3")
      val state3 = state2.withInstanceDeleted(scheduled3)

      Then("should lift offer constraints (the role needs no offers and should be suppressed instead)")
      state3.roleState shouldBe RoleConstraintState.empty
    }
  }
}
