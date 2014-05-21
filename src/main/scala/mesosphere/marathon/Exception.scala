package mesosphere.marathon

/**
  * @author Tobi Knaup
  */

class Exception(msg: String) extends scala.RuntimeException(msg)

class StorageException(msg: String) extends Exception(msg)

class UnknownAppException(id: String) extends Exception(s"App '$id' does not exist")

class BadRequestException(msg: String) extends Exception(msg)

class AppLockedException extends Exception("App is locked by another operation")

class PortRangeExhaustedException(
  minPort: Int,
  maxPort: Int
) extends Exception(s"All ports in the range $minPort-$maxPort are already in use")

/*
 * Task upgrade specific exceptions
 */
abstract class TaskUpgradeFailedException(msg: String) extends Exception(msg)

class HealthCheckFailedException(msg: String) extends TaskUpgradeFailedException(msg)
class TaskFailedException(msg: String) extends TaskUpgradeFailedException(msg)
class ConcurrentTaskUpgradeException(msg: String) extends TaskUpgradeFailedException(msg)
class MissingHealthCheckException(msg: String) extends TaskUpgradeFailedException(msg)
class TaskUpgradeCancelledException(msg: String) extends TaskUpgradeFailedException(msg)