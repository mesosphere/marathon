package mesosphere.marathon.core.appinfo

import mesosphere.marathon.core.readiness.ReadinessCheckResult
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
  maybeReadinessCheckResults: Option[Seq[ReadinessCheckResult]] = None,
  maybeLastTaskFailure: Option[TaskFailure] = None,
  maybeTaskStats: Option[TaskStatsByVersion] = None)

object AppInfo {
  sealed trait Embed
  object Embed {
    case object Tasks extends Embed
    case object Deployments extends Embed
    case object Readiness extends Embed
    case object Counts extends Embed
    case object LastTaskFailure extends Embed
    case object TaskStats extends Embed
  }
}
