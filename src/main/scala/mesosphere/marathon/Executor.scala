package mesosphere.marathon

sealed trait Executor
case class CommandExecutor() extends Executor
case class PathExecutor(path: String) extends Executor

object Executor {
  def dispatch(s: String): Executor = s match {
    case "//cmd"      => CommandExecutor()
    case path: String => PathExecutor(path)
  }
}
