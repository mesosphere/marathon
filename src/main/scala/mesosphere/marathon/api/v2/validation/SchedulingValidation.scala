package mesosphere.marathon
package api.v2.validation

import java.util.regex.Pattern

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ App, Apps, Constraint, PodPlacementPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy, PodUpgradeStrategy, UpgradeStrategy }
import mesosphere.marathon.state.ResourceRole

import scala.util.Try

trait SchedulingValidation {
  import Validation._

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
    strategy.maximumOverCapacity should valid(be == 0.0)
  }

  lazy val validForSingleInstanceApps: Validator[UpgradeStrategy] = validator[UpgradeStrategy] { strategy =>
    strategy.minimumHealthCapacity should valid(be == 0.0)
    strategy.maximumOverCapacity should valid(be == 0.0)
  }

  val complyWithConstraintRules: Validator[Constraint] = new Validator[Constraint] {
    import mesosphere.marathon.raml.ConstraintOperator._
    override def apply(c: Constraint): Result = {
      if (c.fieldName.isEmpty) {
        Failure(Set(RuleViolation(c, "Missing field and operator", None)))
      } else {
        c.operator match {
          case Unique =>
            c.value.fold[Result](Success) { _ => Failure(Set(RuleViolation(c, "Value specified but not used", None))) }
          case Cluster =>
            if (c.value.isEmpty || c.value.map(_.length).getOrElse(0) == 0) {
              Failure(Set(RuleViolation(c, "Missing value", None)))
            } else {
              Success
            }
          case GroupBy =>
            if (c.value.fold(true)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("GROUP_BY may either have no value or an integer value"))))
            }
          case MaxPer =>
            if (c.value.fold(false)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was not specified or is not a number",
                Some("MAX_PER must have an integer value"))))
            }
          case Like | Unlike =>
            c.value.fold[Result] {
              Failure(Set(RuleViolation(c, "A regular expression value must be provided", None)))
            } { p =>
              Try(Pattern.compile(p)) match {
                case util.Success(_) => Success
                case util.Failure(e) =>
                  Failure(Set(RuleViolation(
                    c,
                    s"'$p' is not a valid regular expression",
                    Some(s"$p\n${e.getMessage}"))))
              }
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
    import Protos.Constraint.Operator._

    def failureIllegalOperator(c: Any) = Failure(Set(
      RuleViolation(
        c,
        "Constraint operator must be one of the following UNIQUE, CLUSTER, GROUP_BY, LIKE, MAX_PER or UNLIKE", None)))

    override def apply(c: Seq[String]): Result = {
      def badConstraint(reason: String, desc: Option[String] = None): Result =
        Failure(Set(RuleViolation(c, reason, desc)))
      if (c.length < 2 || c.length > 3) badConstraint("Each constraint must have either 2 or 3 fields")
      else (c.headOption, c.lift(1), c.lift(2)) match {
        case (None, None, _) =>
          badConstraint("Missing field and operator")
        case (Some(_), Some(op), value) =>
          Try(Protos.Constraint.Operator.valueOf(op)) match {
            case scala.util.Success(operator) =>
              operator match {
                case UNIQUE =>
                  value.map(_ => badConstraint("Value specified but not used")).getOrElse(Success)
                case CLUSTER =>
                  value.map(_ => Success).getOrElse(badConstraint("Missing value"))
                case GROUP_BY =>
                  value.fold[Result](Success){ v =>
                    Try(v.toInt).toOption.map(_ => Success).getOrElse(badConstraint(
                      "Value was specified but is not a number",
                      Some("GROUP_BY may either have no value or an integer value")))
                  }
                case LIKE | UNLIKE =>
                  value.map { v =>
                    Try(Pattern.compile(v)).toOption.map(_ => Success).getOrElse(
                      badConstraint(s"'$v' is not a valid regular expression", Some(s"$v")))
                  }.getOrElse(badConstraint("A regular expression value must be provided"))
                case MAX_PER =>
                  value.fold[Result](Success){ v =>
                    Try(v.toInt).toOption.map(_ => Success).getOrElse(badConstraint(
                      "Value was not specified or is not a number",
                      Some("MAX_PER must have an integer value")))
                  }
                case _ =>
                  failureIllegalOperator(c)
              }
            case _ =>
              failureIllegalOperator(c)
          }
        case _ =>
          badConstraint(s"illegal constraint specification ${c.mkString(",")}")
      }
    }
  }
}

object SchedulingValidation extends SchedulingValidation
