package mesosphere.marathon
package core.launchqueue.impl

import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceUpdated}
import mesosphere.marathon.core.launchqueue.impl.OfferConstraints.{RoleConstraintState, ConstraintGroup, AttributeConstraint, TextEquals}
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.{IssueRevive, RoleDirective, UpdateFramework}
import mesosphere.marathon.core.launchqueue.impl.OfferConstraintsStreamLogic

import mesosphere.marathon.state.{AbsolutePathId, AppDefinition}
import org.scalatest.Inside

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

class OfferConstraintsStreamLogicTest extends AkkaUnitTest with Inside {

  def appTiedToHostname(hostname: String): AppDefinition = {
    val constraint = Protos.Constraint.newBuilder
      .setField("hostname")
      .setOperator(Protos.Constraint.Operator.IS)
      .setValue(hostname)
      .build

    AppDefinition(id = AbsolutePathId("/tied/" + hostname), constraints = Set(constraint), role = "role")
  }

  "OfferConstraintsFlow" should {
    "combine 3 events changing offer constraints within the throttle window into a single constraints update" in {
      Given("an OfferConstraintsFlow with a 200 millis minimum update interval")
      val offerConstraintsFlow = OfferConstraintsStreamLogic.offerConstraintsFlow(200.millis)

      When("an instance update is offered for each of 3 apps with constraints")
      val inputSourceQueue = Source.queue[InstanceChangeOrSnapshot](16, OverflowStrategy.fail)
      val outputSinkQueue = Sink.queue[RoleConstraintState]()
      val (input, output) = inputSourceQueue.via(offerConstraintsFlow).toMat(outputSinkQueue)(Keep.both).run

      Future
        .sequence(
          Seq("foo", "bar", "baz").map { hostname => InstanceUpdated(Instance.scheduled(appTiedToHostname(hostname)), None, Nil) }
            .map(input.offer(_))
        )
        .futureValue

      Then("a single offer constraints update is issued")
      inside(output.pull().futureValue) {
        case Some(RoleConstraintState(state)) =>
          state shouldBe Map(
            "role" -> Set(
              ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("foo")))),
              ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("bar")))),
              ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("baz"))))
            )
          )
      }

      When("the instance updates stream completes")
      input.complete()

      Then("no further events are emitted")
      output.pull().futureValue shouldBe None
    }

    "convert two events changing constraints that are separated by an interval longer than the throttle window into two updates" in {
      Given("an OfferConstraintsFlow with a 200 millis minimum update interval")
      val offerConstraintsFlow = OfferConstraintsStreamLogic.offerConstraintsFlow(200.millis)

      When("two app constraints changes are offered and separated by a 300 millis interval")

      val inputSourceQueue = Source.queue[InstanceChangeOrSnapshot](16, OverflowStrategy.fail)
      val outputSinkQueue = Sink.queue[RoleConstraintState]()
      val (input, output) = inputSourceQueue
        .via(Flow[InstanceChangeOrSnapshot].delay(300.millis))
        .via(offerConstraintsFlow)
        .toMat(outputSinkQueue)(Keep.both)
        .run

      Future
        .sequence(
          Seq("foo", "bar").map { hostname => InstanceUpdated(Instance.scheduled(appTiedToHostname(hostname)), None, Nil) }
            .map(input.offer(_))
        )
        .futureValue

      Then("an offer constraints update with constraints of the first app is issued")
      inside(output.pull().futureValue) {
        case Some(RoleConstraintState(state)) =>
          state shouldBe Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("foo"))))))
      }

      And("after that, an update with constraints for both apps is issued")
      inside(output.pull().futureValue) {
        case Some(RoleConstraintState(state)) =>
          state shouldBe Map(
            "role" -> Set(
              ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("foo")))),
              ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("bar"))))
            )
          )
      }

      When("the instance updates stream completes")
      input.complete()

      Then("no further events are emitted")
      output.pull().futureValue shouldBe None
    }

    "deduplicate identical updates of offer constraints" in {
      Given("an OfferConstraintsFlow with a 200 millis minimum update interval")
      val offerConstraintsFlow = OfferConstraintsStreamLogic.offerConstraintsFlow(200.millis)

      When("two identical instance updates with constraints are offered and separated by a 300 millis interval")

      val inputSourceQueue = Source.queue[InstanceChangeOrSnapshot](16, OverflowStrategy.fail)
      val outputSinkQueue = Sink.queue[RoleConstraintState]()
      val (input, output) = inputSourceQueue
        .via(Flow[InstanceChangeOrSnapshot].delay(300.millis))
        .via(offerConstraintsFlow)
        .toMat(outputSinkQueue)(Keep.both)
        .run

      Future
        .sequence(
          Seq("foo", "foo").map { hostname => InstanceUpdated(Instance.scheduled(appTiedToHostname(hostname)), None, Nil) }
            .map(input.offer(_))
        )
        .futureValue

      Then("a single offer constraints update is issued")
      inside(output.pull().futureValue) {
        case Some(RoleConstraintState(state)) =>
          state shouldBe Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("foo"))))))
      }

      When("the instance updates stream completes")
      input.complete()

      Then("no further events are emitted")
      output.pull().futureValue shouldBe None
    }
  }

  val testRoleConstraintsState = RoleConstraintState(
    Map("role" -> Set(ConstraintGroup(Set(AttributeConstraint("hostname", TextEquals("foo"))))))
  )

  "OfferConstraintsInjectionFlow" should {
    "emit no directives before the first UpdateFramework, regardless of constraints updates" in {
      When("a RoleConstraintsState is received without any role directives")

      val result = Source(Seq[RoleDirective]())
        .via(OfferConstraintsStreamLogic.offerConstraintsInjectionFlow(Source(Seq(testRoleConstraintsState))))
        .runWith(Sink.seq)
        .futureValue

      Then("nothing will be emitted")
      inside(result) { case Seq() => () }
    }

    "pair incoming role directives with the latest offer constraints, and re-emit the latest UpdateFramework on constraints update" in {
      val constraintsUpdatePromise = Promise[RoleConstraintState]()
      val constraintsUpdates = Source.future(constraintsUpdatePromise.future)

      When("a sequence of UpdateFramework/IssueRevive directives is offered before the constraints update")
      val updateFramework1 = UpdateFramework(Map("role1" -> OffersWanted), Set.empty, Set.empty)
      val issueRevive1 = IssueRevive(Set("role1"))
      val updateFramework2 = UpdateFramework(Map("role2" -> OffersWanted), Set.empty, Set.empty)
      val issueRevive2 = IssueRevive(Set("role2"))

      val inputSourceQueue = Source.queue[RoleDirective](16, OverflowStrategy.fail)
      val outputSinkQueue = Sink.queue[(RoleDirective, RoleConstraintState)]()

      val (input, output) = inputSourceQueue
        .via(OfferConstraintsStreamLogic.offerConstraintsInjectionFlow(constraintsUpdates))
        .toMat(outputSinkQueue)(Keep.both)
        .run

      val directives = Seq(updateFramework1, issueRevive1, updateFramework2, issueRevive2)
      Future.sequence(directives.map(input.offer(_))).futureValue

      Then("directives should be emitted in the same order, each paired with empty offer constraints")
      directives.map { inputDirective =>
        inside(output.pull().futureValue) {
          case Some((directive, constraints)) =>
            directive shouldBe inputDirective
            constraints shouldBe OfferConstraints.RoleConstraintState.empty
        }
      }

      When("an offer constraints update occurs")
      constraintsUpdatePromise.success(testRoleConstraintsState)

      Then("the latest UpdateFramework should be re-emitted, paired with the new constraints")
      inside(output.pull().futureValue) {
        case Some((directive, constraints)) =>
          directive shouldBe updateFramework2
          constraints shouldBe testRoleConstraintsState
      }

      When("a new UpdateFramework is offered")
      val updateFramework3 = UpdateFramework(Map("role3" -> OffersWanted), Set.empty, Set.empty)
      input.offer(updateFramework3).futureValue

      Then("it should be emitted in pair with the updated constraints")
      inside(output.pull().futureValue) {
        case Some((directive, constraints)) =>
          directive shouldBe updateFramework3
          constraints shouldBe testRoleConstraintsState
      }

      When("the directives stream completes")
      input.complete()

      Then("no further events are emitted")
      output.pull().futureValue shouldBe None
    }

  }
}
