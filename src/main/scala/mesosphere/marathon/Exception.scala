package mesosphere.marathon

/**
 * @author Tobi Knaup
 */

class Exception(msg: String) extends scala.RuntimeException(msg)

class StorageException(msg: String) extends Exception(msg)

class UnknownAppException(id: String) extends Exception(s"Unknown app '$id'")

class BadRequestException(msg: String) extends Exception(msg)
