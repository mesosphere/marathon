package mesosphere.marathon

/**
  * @author Tobi Knaup
  */

class Exception(msg: String) extends scala.RuntimeException(msg)

class StorageException(msg: String) extends Exception(msg)

class UnknownAppException(id: String) extends Exception(s"App '$id' does not exist")

class BadRequestException(msg: String) extends Exception(msg)

/*
 * Task upgrade specific exceptions
 */
abstract class TaskUpgradeFailedException(msg: String) extends Exception(msg)

class HealthCheckFailedException(msg: String) extends TaskUpgradeFailedException(msg)
class TaskFailedException(msg: String) extends TaskUpgradeFailedException(msg)

abstract class NoRollbackNeeded(msg:String) extends TaskUpgradeFailedException(msg)
class ConcurrentTaskUpgradeException(msg: String) extends NoRollbackNeeded(msg)
class MissingHealthCheckException(msg: String) extends NoRollbackNeeded(msg)