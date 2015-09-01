package mesosphere.marathon.core.appinfo

import mesosphere.marathon.state.{ AppDefinition, Identifiable, TaskFailure }

import scala.collection.immutable.Seq

/**
  * An app definition with optional additional data.
  *
  * You can specify which data you want via the AppInfo.Embed types.
  */
case class AppInfo(
  app: AppDefinition,
  maybeTasks: Option[Seq[EnrichedTask]] = None,
  maybeCounts: Option[TaskCounts] = None,
  maybeDeployments: Option[Seq[Identifiable]] = None,
  maybeLastTaskFailure: Option[TaskFailure] = None)

object AppInfo {
  sealed trait Embed
  object Embed {
    case object Tasks extends Embed
    case object Deployments extends Embed
    case object Counts extends Embed
    case object LastTaskFailure extends Embed
  }
}
