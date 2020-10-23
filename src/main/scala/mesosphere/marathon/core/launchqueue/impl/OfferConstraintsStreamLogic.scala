package mesosphere.marathon
package core.launchqueue.impl

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.StrictLogging

import mesosphere.marathon.core.instance.update.{InstanceChangeOrSnapshot, InstanceDeleted, InstanceUpdated, InstancesSnapshot}
import mesosphere.marathon.core.launchqueue.impl.OfferConstraints
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersStreamLogic.{IssueRevive, RoleDirective, UpdateFramework}

import mesosphere.marathon.stream.RateLimiterFlow

import scala.concurrent.duration.FiniteDuration

object OfferConstraintsStreamLogic extends StrictLogging {

  // Generates rate-limited offer constraint updates from instance updates.
  def offerConstraintsFlow(
      minUpdateInterval: FiniteDuration
  ): Flow[InstanceChangeOrSnapshot, OfferConstraints.RoleConstraintState, NotUsed] = {
    val updateDeduplicationFlow = Flow[OfferConstraints.RoleConstraintState]
      .sliding(2)
      .map {
        case Seq(previous, current) =>
          if (previous == current) None else Some(current)
        case _ =>
          logger.info(s"Offer constraints flow is terminating")
          None
      }
      .collect { case Some(state) => state }

    Flow[InstanceChangeOrSnapshot]
      .scan(OfferConstraints.State.empty) {
        case (current, snapshot: InstancesSnapshot) => current.withSnapshot(snapshot)
        case (current, InstanceUpdated(updated, _, _)) => current.withInstanceAddedOrUpdated(updated)
        case (current, InstanceDeleted(deleted, _, _)) => current.withInstanceDeleted(deleted)
      }
      .buffer(1, OverflowStrategy.dropHead) // While we are back-pressured, we drop older interim frames
      .via(RateLimiterFlow.apply(minUpdateInterval))
      .map(_.roleState)
      .via(updateDeduplicationFlow)
      .map { state => { logger.info(s"Changing offer constraints to: ${state}"); state } }
  }

  /**
    * Emits each RoleDirective together with the latest offer constraints state.
    * Empty constraints are used if no constraints have been generated yet.
    *
    * On offer constraints update, RE-EMITS the latest UpdateFramework directive
    * with the new constraints. Before the first UpdateFramework, doesn't emit
    * anything on constraints updates.
    *
   * @param minUpdateInterval - The maximum rate at which we allow offer constraint updates to be emitted.
    */
  def offerConstraintsInjectionFlow(
      offerConstraintsSource: Source[OfferConstraints.RoleConstraintState, NotUsed]
  ): Flow[RoleDirective, (RoleDirective, OfferConstraints.RoleConstraintState), NotUsed] = {

    case class Latest(
        updateFramework: Option[UpdateFramework],
        constraints: OfferConstraints.RoleConstraintState,
        directive: Option[RoleDirective]
    ) {

      def withDirectiveOrConstraints(directiveOrConstraints: Either[RoleDirective, OfferConstraints.RoleConstraintState]): Latest =
        directiveOrConstraints match {
          case Left(issueRevive: IssueRevive) =>
            copy(directive = Some(issueRevive))
          case Left(newUpdateFramework: UpdateFramework) =>
            copy(updateFramework = Some(newUpdateFramework), directive = Some(newUpdateFramework))
          case Right(constraints) =>
            copy(constraints = constraints, directive = updateFramework)
        }
    }

    object Latest { def empty = Latest(None, OfferConstraints.RoleConstraintState.empty, None) }

    Flow[RoleDirective]
      .map(Left(_))
      .merge(offerConstraintsSource.map(Right(_)))
      .scan(Latest.empty) { (current, element) => current.withDirectiveOrConstraints(element) }
      .collect { case Latest(_, constraints, Some(directive)) => (directive, constraints) }
  }
}
