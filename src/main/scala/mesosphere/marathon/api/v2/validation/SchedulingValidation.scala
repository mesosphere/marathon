package mesosphere.marathon
package api.v2.validation

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ App, Apps, Constraint, ConstraintOperator, PodPlacementPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy, PodUpgradeStrategy, UpgradeStrategy }
import mesosphere.marathon.state.ResourceRole

import scala.util.Try

trait SchedulingValidation {
  import Validation._
  import SchedulingValidationMessages._

  val backoffStrategyValidator = validator[PodSchedulingBackoffStrategy] { bs =>
    bs.backoff should be >= 0.0
    bs.backoffFactor should be >= 0.0
    bs.maxLaunchDelay should be >= 0.0
  }

  val upgradeStrategyValidator = validator[PodUpgradeStrategy] { us =>
    us.maximumOverCapacity should be >= 0.0
    us.maximumOverCapacity should be <= 1.0
    us.minimumHealthCapacity should be >= 0.0
    us.minimumHealthCapacity should be <= 1.0
  }

  val complyWithSingleInstanceLabelRules: Validator[App] =
    isTrue("Single instance app may only have one instance") { app =>
      (!isSingleInstance(app)) || (app.instances <= 1)
    }

  def isSingleInstance(app: App): Boolean = app.labels.get(Apps.LabelSingleInstanceApp).contains("true")

  val complyWithUpgradeStrategyRules: Validator[App] = validator[App] { app =>
    app.upgradeStrategy is optional(implied(isSingleInstance(app))(validForSingleInstanceApps))
    app.upgradeStrategy is optional(implied(app.residency.nonEmpty)(validForResidentTasks))
  }

  lazy val validForResidentTasks: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity is between(0.0, 1.0)
    strategy.maximumOverCapacity should be == 0.0
  }

  lazy val validForSingleInstanceApps: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should be == 0.0
    strategy.maximumOverCapacity should be == 0.0
  }

  val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import mesosphere.marathon.raml.ConstraintOperator._
    override def apply(c: Constraint): Result = {
      def failure(constraintViolation: String) =
        Failure(Set(RuleViolation(c, constraintViolation)))
      if (c.fieldName.isEmpty) {
        failure(ConstraintRequiresField)
      } else {
        c.operator match {
          case Unique =>
            c.value.fold[Result](Success) { _ => failure(ConstraintUniqueDoesNotAcceptValue) }
          case Cluster =>
            // value is completely optional for CLUSTER
            Success
          case GroupBy =>
            if (c.value.fold(true)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              failure(ConstraintGroupByMustBeEmptyOrInt)
            }
          case MaxPer =>
            if (c.value.fold(false)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              failure(ConstraintMaxPerRequiresInt)
            }
          case Like | Unlike =>
            c.value.fold[Result] {
              failure(ConstraintLikeAnUnlikeRequireRegexp)
            } { p =>
              Try(Pattern.compile(p)) match {
                case util.Success(_) => Success
                case util.Failure(e) => failure(InvalidRegularExpression)
              }
            }
          case Is =>
            c.value.getOrElse("") match {
              case MesosLiteralOrFloatValue() =>
                Success
              case _ =>
                failure(IsOnlySupportsText)
            }
        }
      }
    }
  }

  val placementStrategyValidator = validator[PodPlacementPolicy] { ppp =>
    ppp.acceptedResourceRoles.toSet is empty or ResourceRole.validAcceptedResourceRoles(false) // TODO(jdef) assumes pods!! change this to properly support apps
    ppp.constraints is empty or every(complyWithConstraintRules)
  }

  val schedulingValidator = validator[PodSchedulingPolicy] { psp =>
    psp.backoff is optional(backoffStrategyValidator)
    psp.upgrade is optional(upgradeStrategyValidator)
    psp.placement is optional(placementStrategyValidator)
  }

  val complyWithAppConstraintRules: Validator[Seq[String]] = new Validator[Seq[String]] {
    def failureIllegalOperator(c: Any) = Failure(Set(RuleViolation(c, ConstraintOperatorInvalid)))

    override def apply(c: Seq[String]): Result = {
      def badConstraint(reason: String): Result =
        Failure(Set(RuleViolation(c, reason)))
      if (c.length < 2 || c.length > 3) badConstraint("Each constraint must have either 2 or 3 fields")
      else (c.headOption, c.lift(1), c.lift(2)) match {
        case (None, None, _) =>
          badConstraint("Missing field and operator")
        case (Some(field), Some(op), value) if field.nonEmpty =>
          ConstraintOperator.fromString(op.toUpperCase) match {
            case Some(operator) =>
              // reuse the rules from pod constraint validation so that we're not maintaining redundant rule sets
              complyWithConstraintRules(Constraint(fieldName = field, operator = operator, value = value))
            case _ =>
              failureIllegalOperator(c)
          }
        case _ =>
          badConstraint(IllegalConstraintSpecification)
      }
    }
  }
}

object SchedulingValidation extends SchedulingValidation

object SchedulingValidationMessages {
  /* IS currently supports text and scalar values. By disallowing ranges / sets, we can add support for them later
   * without breaking the API. */
  val MesosLiteralOrFloatValue = "^[a-zA-Z0-9_/.-]*$".r

  val IsOnlySupportsText = "IS only supports Mesos text and float values (see http://mesos.apache.org/documentation/latest/attributes-resources/)"
  val InOnlySupportsSets = "IN value expects a Mesos set value (see http://mesos.apache.org/documentation/latest/attributes-resources/)"

  val ConstraintRequiresField = "missing field for constraint declaration"
  val InvalidRegularExpression = "is not a valid regular expression"
  val ConstraintLikeAnUnlikeRequireRegexp = "LIKE and UNLIKE require a non-empty, regular expression value"
  val ConstraintMaxPerRequiresInt = "MAX_PER requires an integer value"
  val ConstraintGroupByMustBeEmptyOrInt = "GROUP BY must define an integer value or else no value at all"
  val ConstraintUniqueDoesNotAcceptValue = "UNIQUE does not accept a value"
  val IllegalConstraintSpecification = "illegal constraint specification"
  val ConstraintOperatorInvalid = "operator must be one of the following UNIQUE, CLUSTER, GROUP_BY, LIKE, MAX_PER or UNLIKE"
}
