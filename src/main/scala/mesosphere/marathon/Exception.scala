package mesosphere.marathon

import mesosphere.marathon.state.PathId

class Exception(msg: String) extends scala.RuntimeException(msg)

class StorageException(msg: String) extends Exception(msg)

class UnknownAppException(id: PathId) extends Exception(s"App '$id' does not exist")

class BadRequestException(msg: String) extends Exception(msg)

case class AppLockedException(deploymentIds: Seq[String] = Nil)
  extends Exception(
    "App is locked by one or more deployments. " +
      "Override with the option '?force=true'. " +
      "View details at '/v2/deployments/<DEPLOYMENT_ID>'."
  )

case class PortResourceException(msg: String) extends Exception(msg)

class PortRangeExhaustedException(
  val minPort: Int,
  val maxPort: Int) extends Exception(s"All ports in the range $minPort-$maxPort are already in use")

case class UpgradeInProgressException(msg: String) extends Exception(msg)

case class CanceledActionException(msg: String) extends Exception(msg)

case class ConflictingChangeException(msg: String) extends Exception(msg)

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

/*
 * Deployment specific exceptions
 */
abstract class DeploymentFailedException(msg: String) extends Exception(msg)

class DeploymentCanceledException(msg: String) extends DeploymentFailedException(msg)
class AppStartCanceledException(msg: String) extends DeploymentFailedException(msg)
class AppStopCanceledException(msg: String) extends DeploymentFailedException(msg)
class ResolveArtifactsCanceledException(msg: String) extends DeploymentFailedException(msg)
