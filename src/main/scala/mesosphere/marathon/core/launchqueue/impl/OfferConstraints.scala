package mesosphere.marathon
package core.launchqueue.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.instance.update.InstancesSnapshot
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.launchqueue.impl.ReviveOffersState.Role
import mesosphere.marathon.state.RunSpecConfigRef

import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.mesos.Constraints

import org.apache.mesos.scheduler.Protos.{OfferConstraints => MesosOfferConstraints}
import org.apache.mesos.scheduler.Protos.{AttributeConstraint => MesosAttributeConstraint}

object OfferConstraints {
  // Intermediate representation of a Mesos attribute constraint predicate.
  sealed trait Predicate;

  case class Exists() extends Predicate
  case class NotExists() extends Predicate
  case class TextEquals(value: String) extends Predicate
  case class TextNotEquals(value: String) extends Predicate
  case class TextMatches(regex: String) extends Predicate
  case class TextNotMatches(regex: String) extends Predicate

  private def buildPredicateProto(predicate: Predicate, builder: MesosAttributeConstraint.Predicate.Builder): Unit =
    predicate match {
      case Exists() => builder.getExistsBuilder();
      case NotExists() => builder.getNotExistsBuilder();
      case TextEquals(value) => builder.getTextEqualsBuilder.setValue(value);
      case TextNotEquals(value) => builder.getTextNotEqualsBuilder.setValue(value);
      case TextMatches(regex) => builder.getTextMatchesBuilder.setRegex(regex);
      case TextNotMatches(regex) => builder.getTextNotMatchesBuilder.setRegex(regex);
    }

  // Intermediate representation of a Mesos attribute constraint.
  case class AttributeConstraint(field: String, predicate: Predicate) {
    def buildProto(builder: MesosAttributeConstraint.Builder): Unit = {
      Constraints.buildSelectorProto(field, builder.getSelectorBuilder());
      buildPredicateProto(predicate, builder.getPredicateBuilder());
    }
  }

  private def getAgnosticConstraint(constraint: Constraint): AttributeConstraint = {
    val predicate: Predicate = constraint.getOperator match {
      case Operator.LIKE => TextMatches(constraint.getValue)
      case Operator.UNLIKE => TextNotMatches(constraint.getValue)
      case Operator.UNIQUE => Exists()
      case Operator.GROUP_BY => Exists()
      case Operator.MAX_PER => Exists()
      case Operator.CLUSTER => if (constraint.getValue.isEmpty) Exists() else TextEquals(constraint.getValue)
      case Operator.IS => TextEquals(constraint.getValue)
    }

    AttributeConstraint(constraint.getField, predicate)
  }

  /*
   * An attribute constraint that is brought to life by existence of
   * an active instance under app's constraint, and is applied only as soon as
   * the app has enough active instances inducing this constraint.
   * This is used to translate GROUP_BY/UNIQUE/MAX_PER into offer constraints.
   *
   * Example: consider an app with a constraint "foo:MAX_PER:2".
   * After the first launch on an agent with an attribute "foo:bar",
   * the app has a single instance that set
   * `Induced(AttributeConstraint("foo", TextNotEquals("bar")), 2)`
   * which is not applied.
   * After the second launch on an agent with an attribute "foo:bar",
   * the app has two such instances, hence
   * `AttributeConstraint("foo", TextNotEquals("bar"))`
   * is applied from then onwards.
   */
  case class Induced(attributeConstraint: AttributeConstraint, minInstanceCountToApply: Int)

  /*
   * Returns offer constraints that should be set for further offers
   * due to already existing Instance for the RunSpec in question.
   */
  private def getInducedConstraint(constraint: Constraint, placed: Instance): Option[Induced] = {

    def makeInduced(predicate: Predicate, minInstanceCountToApply: Int): Some[Induced] =
      Some(Induced(AttributeConstraint(constraint.getField, predicate), minInstanceCountToApply))

    val placedValueReader = Constraints.readerForField(constraint.getField)._2

    placedValueReader(placed) match {
      case Some(value) =>
        constraint.getOperator match {
          case Operator.IS => None
          case Operator.LIKE => None
          case Operator.UNLIKE => None
          case Operator.CLUSTER => makeInduced(TextEquals(value), 1)
          case Operator.UNIQUE => makeInduced(TextNotEquals(value), 1)
          case Operator.MAX_PER => makeInduced(TextNotEquals(value), constraint.getValue.toInt)

          // TODO: Support induced constraints for GROUP_BY
          case Operator.GROUP_BY => None

        }
      // This covers the case of a scheduled instance
      case None => None
    }
  }

  private def getInducedByInstance(instance: Instance): Set[Induced] =
    if (!instance.isActive)
      Set.empty
    else
      instance.runSpec.constraints
        .map(getInducedConstraint(_, instance))
        .flatMap(identity)
        .to(Set)

  case class ConstraintGroup(constraints: Set[AttributeConstraint]) {
    def buildProto(builder: MesosOfferConstraints.RoleConstraints.Group.Builder): Unit = {
      constraints.foreach { _.buildProto(builder.addAttributeConstraintsBuilder()) }
    }
  }

  object ConstraintGroup { def empty = ConstraintGroup(Set.empty) }

  case class RunSpecState(
      role: Role,
      constraints: Set[Constraint],
      induced: Map[Instance.Id, Set[Induced]],
      scheduledInstances: Set[Instance.Id],
      instancesToUnreserve: Set[Instance.Id]
  ) {

    def isEmpty(): Boolean = induced.isEmpty && scheduledInstances.isEmpty && instancesToUnreserve.isEmpty

    def willChangeOnUpdate(instance: Instance): Boolean =
      role != instance.runSpec.role ||
        constraints != instance.runSpec.constraints ||
        induced.getOrElse(instance.instanceId, Set.empty) != getInducedByInstance(instance) ||
        scheduledInstances.contains(instance.instanceId) != instance.isScheduled ||
        instancesToUnreserve(instance.instanceId) != ReviveOffersState.shouldUnreserve(instance)

    def withInstanceAddedOrUpdated(instance: Instance): RunSpecState = {
      val id = instance.instanceId
      val inducedByInstance = getInducedByInstance(instance)

      copy(
        role = instance.runSpec.role,
        constraints = instance.runSpec.constraints,
        induced = if (inducedByInstance.isEmpty) induced - id else induced + (id -> inducedByInstance),
        scheduledInstances = if (instance.isScheduled) scheduledInstances + id else scheduledInstances - id,
        instancesToUnreserve = if (ReviveOffersState.shouldUnreserve(instance)) instancesToUnreserve + id else instancesToUnreserve - id
      )
    }

    def withInstanceDeleted(instance: Instance): RunSpecState = {
      val newInduced = induced - instance.instanceId
      val newScheduledInstances = scheduledInstances - instance.instanceId
      val newInstancesToUnreserve = instancesToUnreserve - instance.instanceId
      if (newInduced.isEmpty && newScheduledInstances.isEmpty && newInstancesToUnreserve.isEmpty)
        RunSpecState.empty
      else
        copy(role, constraints, newInduced, newScheduledInstances, newInstancesToUnreserve)
    }

    def toGroup(): Option[ConstraintGroup] =
      if (!instancesToUnreserve.isEmpty)
        Some(ConstraintGroup.empty)
      else if (scheduledInstances.isEmpty)
        None
      else {
        val allInducedConstraints = induced.values.flatten
        val effectiveInducedConstraints = allInducedConstraints
          .groupBy(identity)
          .valuesIterator
          .map { (induced: Iterable[Induced]) =>
            if (induced.size >= induced.head.minInstanceCountToApply)
              Some(induced.head.attributeConstraint)
            else None
          }
          .flatten

        Some(ConstraintGroup((effectiveInducedConstraints ++ constraints.map(getAgnosticConstraint)).to(Set)))
      }
  }

  object RunSpecState {
    def empty = RunSpecState("", Set.empty, Map.empty, Set.empty, Set.empty)

    def fromSnapshotInstances(instances: Iterable[Instance]): RunSpecState =
      RunSpecState(
        role = instances.head.runSpec.role,
        constraints = instances.head.runSpec.constraints,
        induced = instances.flatMap { instance =>
          {
            val inducedByInstance = getInducedByInstance(instance)
            if (inducedByInstance.isEmpty) None else Some(instance.instanceId -> inducedByInstance)
          }
        }.to(Map),
        scheduledInstances = instances.flatMap { instance =>
          if (instance.isScheduled) Some(instance.instanceId) else None
        }.to(Set),
        instancesToUnreserve = instances.flatMap { instance =>
          if (ReviveOffersState.shouldUnreserve(instance)) Some(instance.instanceId) else None
        }.to(Set)
      )
  }

  case class RoleConstraintState(groupsByRole: Map[Role, Set[ConstraintGroup]]) {
    def filterRoles(roles: Set[String]): RoleConstraintState = {
      RoleConstraintState(groupsByRole = groupsByRole.view.filterKeys(roles).toMap)
    }

    def toProto(): MesosOfferConstraints = {
      val builder = MesosOfferConstraints.newBuilder();

      groupsByRole.foreach {
        case (role, groups) =>
          val roleConstraintsBuilder = MesosOfferConstraints.RoleConstraints.newBuilder()

          groups.foreach { _.buildProto(roleConstraintsBuilder.addGroupsBuilder()) }
          builder.putRoleConstraints(role, roleConstraintsBuilder.build())
      }

      builder.build()
    }
  }

  object RoleConstraintState { val empty = RoleConstraintState(Map.empty) }

  case class State(state: Map[RunSpecConfigRef, RunSpecState]) extends StrictLogging {

    def withSnapshot(snapshot: InstancesSnapshot): State =
      State(
        snapshot.instances
          .groupBy(_.runSpec.configRef)
          .view
          .mapValues(RunSpecState.fromSnapshotInstances)
          .toMap
      )

    def withInstanceAddedOrUpdated(instance: Instance): State = {
      val configRef = instance.runSpec.configRef
      val runSpecState: RunSpecState = state.getOrElse(configRef, RunSpecState.empty)

      if (runSpecState.willChangeOnUpdate(instance))
        copy(state + (configRef -> runSpecState.withInstanceAddedOrUpdated(instance)))
      else
        this
    }

    def withInstanceDeleted(instance: Instance): State = {
      val configRef = instance.runSpec.configRef
      val newRunSpecState = state
        .getOrElse(configRef, RunSpecState.empty)
        .withInstanceDeleted(instance)

      copy(
        if (newRunSpecState.isEmpty())
          state - configRef
        else
          state + (configRef -> newRunSpecState)
      )
    }

    lazy val roleState = RoleConstraintState({
      val runSpecStatesByRole = state.values.groupBy(_.role)

      runSpecStatesByRole.flatMap {
        case (role: Role, runSpecStates: Iterable[RunSpecState]) =>
          val groups: Set[ConstraintGroup] = runSpecStates.flatMap(_.toGroup).toSet
          val roleShouldBeSuppressed = groups.isEmpty
          val roleNeedsUnconstrainedOffers = groups.contains(ConstraintGroup.empty)

          // NOTE: Decision to suppress a role is made independently by the ReviveOffersState.
          if (roleShouldBeSuppressed || roleNeedsUnconstrainedOffers)
            None
          else
            Some(role -> groups)
      }
    })
  }

  object State { val empty = State(Map.empty) }
}
