package mesosphere.marathon.plugin.task

import mesosphere.marathon.plugin.AppDefinition
import mesosphere.marathon.plugin.plugin.Plugin
import org.apache.mesos.Protos.TaskInfo

/**
  * AppTaskProcessor is a mutates Mesos task info's given some app specification.
  */
trait AppTaskProcessor extends Function2[AppDefinition, TaskInfo.Builder, Unit] with Plugin

object AppTaskProcessor {
  import scala.language.implicitConversions

  def apply(f: (AppDefinition, TaskInfo.Builder) => Unit): AppTaskProcessor = new AppTaskProcessor {
    override def apply(app: AppDefinition, b: TaskInfo.Builder): Unit = f(app, b)
  }

  implicit def combine(procs: Seq[AppTaskProcessor]): AppTaskProcessor =
    apply { (app: AppDefinition, b: TaskInfo.Builder) =>
      procs.size match {
        case 0 =>
        case 1 => procs.headOption.map(_(app, b))
        case _ => for (p <- procs) { p(app, b) }
      }
    }
}
