package mesosphere.marathon
import com.wix.accord.Failure
import mesosphere.marathon.state.{PathId, Timestamp}

class Exception(msg: String, cause: Throwable = null) extends scala.RuntimeException(msg, cause)

// TODO(MARATHON-8202) - convert to Rejection
case class PathNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"Path '$id' does not exist" + version.fold("")(v => s" in version $v")
)

// TODO(MARATHON-8202) - convert to Rejection
case class AppNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"App '$id' does not exist" + version.fold("")(v => s" in version $v")
)

// TODO(MARATHON-8202) - convert to Rejection
case class PodNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"Pod '$id' does not exist" + version.fold("")(v => s" in version $v")
)

// TODO(MARATHON-8202) - convert to Rejection
case class UnknownGroupException(id: PathId) extends Exception(s"Group '$id' does not exist")

case class WrongConfigurationException(message: String) extends Exception(message)

// TODO(MARATHON-8202) - convert to Rejection
case class AppLockedException(deploymentIds: Seq[String] = Nil)
  extends Exception(
    "App is locked by one or more deployments. " +
      "Override with the option '?force=true'. " +
      "View details at '/v2/deployments/<DEPLOYMENT_ID>'."
  )

// TODO(MARATHON-8202) - convert to Rejection
case class TooManyRunningDeploymentsException(maxNum: Int) extends Exception(
  s"Max number ($maxNum) of running deployments is achieved. Wait for existing deployments to complete or cancel one" +
    "using force=true parameter. You can increase max deployment number by using --max_running_deployments parameter " +
    "but be advised that this can have negative effects on the performance."
)

// TODO(MARATHON-8202) - convert to Rejection
class PortRangeExhaustedException(
    val minPort: Int,
    val maxPort: Int) extends Exception(s"All ports in the range [$minPort-$maxPort) are already in use")

case class CanceledActionException(msg: String) extends Exception(msg)

case class ConflictingChangeException(msg: String) extends Exception(msg)

/**
  * Is thrown if an object validation is not successful.
  *
  * TODO(MARATHON-8202) - convert to Rejection
  *
  * @param obj object which is not valid
  * @param failure validation information kept in a Failure object
  */
case class ValidationFailedException(obj: Any, failure: Failure) extends Exception(s"Validation failed: $failure")

/**
  * Thrown for errors during [[Normalization]]. Validation should normally be checking for invalid data structures
  * that would lead to errors during normalization, so these exceptions are very unexpected.
  * @param msg provides details
  */
case class NormalizationException(msg: String) extends Exception(msg)

case class SerializationFailedException(message: String) extends Exception(message)

/*
 * Task upgrade specific exceptions
 */
abstract class TaskUpgradeFailedException(msg: String) extends Exception(msg)

class HealthCheckFailedException(msg: String) extends TaskUpgradeFailedException(msg)
class TaskFailedException(msg: String) extends TaskUpgradeFailedException(msg)
class ConcurrentTaskUpgradeException(msg: String) extends TaskUpgradeFailedException(msg)
class MissingHealthCheckException(msg: String) extends TaskUpgradeFailedException(msg)
class AppDeletedException(msg: String) extends TaskUpgradeFailedException(msg)
class TaskUpgradeCanceledException(msg: String) extends TaskUpgradeFailedException(msg)
class KillingInstancesFailedException(msg: String) extends Exception(msg)

/*
 * Deployment specific exceptions
 */
abstract class DeploymentFailedException(msg: String) extends Exception(msg)

class DeploymentCanceledException(msg: String) extends DeploymentFailedException(msg)

/*
 * Store specific exceptions
 */
class StoreCommandFailedException(msg: String, cause: Throwable = null) extends Exception(msg, cause)
class MigrationFailedException(msg: String, cause: Throwable = null) extends Exception(msg, cause)

/**
  * Instances of this exception class are expected to be used to cancel
  * an on-going migration. It is imperative to throw such an exception
  * before writing anything to a persistence store.
  */
case class MigrationCancelledException(msg: String, cause: Throwable)
  extends mesosphere.marathon.Exception(msg, cause)
