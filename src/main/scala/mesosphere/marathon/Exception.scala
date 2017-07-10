package mesosphere.marathon

import com.wix.accord.Failure
import mesosphere.marathon.state.{ PathId, Timestamp }

@SuppressWarnings(Array("NullAssignment"))
class Exception(msg: String, cause: Throwable = null) extends scala.RuntimeException(msg, cause)

case class PathNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"Path '$id' does not exist" + version.fold("")(v => s" in version $v")
)

case class AppNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"App '$id' does not exist" + version.fold("")(v => s" in version $v")
)

case class PodNotFoundException(id: PathId, version: Option[Timestamp] = None) extends Exception(
  s"Pod '$id' does not exist" + version.fold("")(v => s" in version $v")
)

case class UnknownGroupException(id: PathId) extends Exception(s"Group '$id' does not exist")

case class WrongConfigurationException(message: String) extends Exception(message)

class BadRequestException(msg: String) extends Exception(msg)

case class AppLockedException(deploymentIds: Seq[String] = Nil)
  extends Exception(
    "App is locked by one or more deployments. " +
      "Override with the option '?force=true'. " +
      "View details at '/v2/deployments/<DEPLOYMENT_ID>'."
  )

class PortRangeExhaustedException(
  val minPort: Int,
  val maxPort: Int) extends Exception(s"All ports in the range [$minPort-$maxPort) are already in use")

case class UpgradeInProgressException(msg: String) extends Exception(msg)

case class CanceledActionException(msg: String) extends Exception(msg)

case class ConflictingChangeException(msg: String) extends Exception(msg)

case class AccessDeniedException(msg: String = "Authorization Denied") extends Exception(msg)

/**
  * Is thrown if an object validation is not successful.
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
class AppStartCanceledException(msg: String) extends DeploymentFailedException(msg)
class AppStopCanceledException(msg: String) extends DeploymentFailedException(msg)
class ResolveArtifactsCanceledException(msg: String) extends DeploymentFailedException(msg)

/*
 * Store specific exceptions
 */
@SuppressWarnings(Array("NullAssignment"))
class StoreCommandFailedException(msg: String, cause: Throwable = null) extends Exception(msg, cause)
@SuppressWarnings(Array("NullAssignment"))
class MigrationFailedException(msg: String, cause: Throwable = null) extends Exception(msg, cause)
