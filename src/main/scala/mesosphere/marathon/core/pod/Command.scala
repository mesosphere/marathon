package mesosphere.marathon.core.pod

/**
  * Defines a command that shall be executed by a task or HealthCheck.
  */
sealed trait Command
case class ShellCommand(value: String) extends Command
case class ArgvCommand(values: Iterable[String]) extends Command
