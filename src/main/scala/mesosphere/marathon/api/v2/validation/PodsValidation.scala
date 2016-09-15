package mesosphere.marathon.api.v2.validation

// scalastyle:off
import java.util.regex.Pattern

import com.wix.accord.dsl._
import com.wix.accord.{ Failure, Result, RuleViolation, Success, Validator }
import mesosphere.marathon.Features
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.raml.{ Constraint, EnvVars, FixedPodScalingPolicy, PodContainer, Network, Pod, PodPlacementPolicy, PodScalingPolicy, PodSchedulingBackoffStrategy, PodSchedulingPolicy, PodUpgradeStrategy, Resources, Secrets, Volume }
import mesosphere.marathon.state.{ PathId, ResourceRole }

import scala.collection.immutable.Seq
import scala.util.Try
// scalastyle:on

/**
  * Defines implicit validation for pods
  */
trait PodsValidation {
  import Validation._

  val NamePattern = """^[a-z0-9]([-a-z0-9]*[a-z0-9])?$""".r
  val validName: Validator[String] = validator[String] { name =>
    name should matchRegexWithFailureMessage(
      NamePattern,
      "must contain only alphanumeric chars or hyphens, and must begin with a letter")
    name.length should be > 0
    name.length should be < 64
  }

  val networkValidator: Validator[Network] = validator[Network] { network =>
    network.name.each is valid(validName)
  }

  val networksValidator: Validator[Seq[Network]] = isTrue[Seq[Network]]("Duplicate networks are not allowed") { nets =>
    val unnamedAtMostOnce = nets.count(_.name.isEmpty) < 2
    val realNamesAtMostOnce: Boolean = !nets.flatMap(_.name).groupBy(name => name).exists(_._2.size > 1)
    unnamedAtMostOnce && realNamesAtMostOnce
  }

  val envValidator = validator[EnvVars] { env =>
    env.values.keys is every(validName)
  }

  val resourceValidator = validator[Resources] { resource =>
    resource.cpus should be >= 0.0
    resource.mem should be >= 0.0
    resource.disk should be >= 0.0
    resource.gpus should be >= 0
  }

  val containerValidator: Validator[PodContainer] = validator[PodContainer] { container =>
    container.resources is valid(resourceValidator)
    container.environment is optional(envValidator)
  }

  val volumeValidator: Validator[Volume] = validator[Volume] { volume =>
    volume.name is valid(validName)
    volume.host is optional(notEmpty)
  }

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

  val secretValidator = validator[Secrets] { s =>
    s.values.values.each
  }

  // scalastyle:off
  val complyWithContraintRules: Validator[Constraint] = new Validator[Constraint] {
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
          case GroupBy | MaxPer =>
            if (c.value.fold(true)(i => Try(i.toInt).isSuccess)) {
              Success
            } else {
              Failure(Set(RuleViolation(
                c,
                "Value was specified but is not a number",
                Some("GROUP_BY may either have no value or an integer value"))))
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
  // scalastyle: on

  val placementStrategyValidator = validator[PodPlacementPolicy] { ppp =>
    ppp.acceptedResourceRoles.toSet is empty or ResourceRole.validAcceptedResourceRoles(false)
    ppp.constraints is empty or every(complyWithContraintRules)
  }

  val schedulingValidator = validator[PodSchedulingPolicy] { psp =>
    psp.backoff is optional(backoffStrategyValidator)
    psp.upgrade is optional(upgradeStrategyValidator)
    psp.placement is optional(placementStrategyValidator)
  }

  val fixedPodScalingPolicyValidator = validator[FixedPodScalingPolicy] { f =>
    f.instances should be >= 0
    f.maxInstances.getOrElse(0) should be >= 0
  }

  val scalingValidator: Validator[PodScalingPolicy] = new Validator[PodScalingPolicy] {
    override def apply(v1: PodScalingPolicy): Result = v1 match {
      case fsf: FixedPodScalingPolicy => fixedPodScalingPolicyValidator(fsf)
      case _ => Failure(Set(RuleViolation(v1, "Not a fixed scaling policy", None)))
    }
  }

  def podDefValidator(enabledFeatures: Set[String]): Validator[Pod] = validator[Pod] { pod =>
    PathId(pod.id) is valid(PathId.absolutePathValidator)
    pod.user is optional(notEmpty)
    pod.environment is optional(envValidator)
    pod.containers is notEmpty and every(containerValidator)
    pod.secrets is optional(secretValidator)
    pod.secrets is empty or featureEnabled(enabledFeatures, Features.SECRETS)
    pod.volumes is every(volumeValidator)
    pod.networks is valid(networksValidator)
    pod.networks is every(networkValidator)
    pod.scheduling is optional(schedulingValidator)
    pod.scaling is optional(scalingValidator)
  }
}

object PodsValidation extends PodsValidation