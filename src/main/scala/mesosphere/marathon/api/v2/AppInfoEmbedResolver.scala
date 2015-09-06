package mesosphere.marathon.api.v2

import java.util

import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.core.appinfo.AppInfo.Embed
import org.slf4j.LoggerFactory

/**
  * Resolves AppInfo.Embed from query parameters.
  */
private[v2] object AppInfoEmbedResolver {
  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val EmbedAppsPrefixes = Set("apps.", "app.")

  private[this] val EmbedTasks = "tasks"
  private[this] val EmbedDeployments = "deployments"

  /* deprecated, use lastTaskFailure, tasks, deployments instead */
  private[this] val EmbedTasksAndFailures = "failures"
  private[this] val EmbedLastTaskFailure = "lastTaskFailure"
  private[this] val EmbedCounts = "counts"
  private[this] val EmbedTaskStats = "taskStats"

  /**
    * Converts embed arguments to our internal representation.
    *
    * Accepts the arguments with all prefixes or even no prefix at all
    * to avoid subtle user errors confusing the two.
    */
  def resolve(embed: util.Set[String]): Set[Embed] = {
    def mapEmbedStrings(prefix: String, withoutPrefix: String): Set[AppInfo.Embed] = withoutPrefix match {
      case EmbedTasks => Set(AppInfo.Embed.Tasks, /* deprecated */ AppInfo.Embed.Deployments)
      case EmbedTasksAndFailures =>
        log.warn(s"Using deprecated embed=s$prefix$withoutPrefix. " +
          s"Use ${prefix}tasks, ${prefix}lastTaskFailure, ${prefix}deployments instead.")
        Set(AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments)
      case EmbedDeployments     => Set(AppInfo.Embed.Deployments)
      case EmbedLastTaskFailure => Set(AppInfo.Embed.LastTaskFailure)
      case EmbedCounts          => Set(AppInfo.Embed.Counts)
      case EmbedTaskStats       => Set(AppInfo.Embed.TaskStats)
      case unknown: String =>
        log.warn(s"unknown embed argument: $prefix$unknown")
        Set.empty
    }

    def separatePrefix(embedMe: String): (String, String) = {
      val removablePrefix = EmbedAppsPrefixes.find(embedMe.startsWith(_)).getOrElse("")
      val withoutPrefix = embedMe.substring(removablePrefix.length)
      (removablePrefix, withoutPrefix)
    }

    import scala.collection.JavaConverters._
    val embedWithSeparatedPrefixes = embed.asScala.map(separatePrefix)
    embedWithSeparatedPrefixes.flatMap { case (prefix, withoutPrefix) => mapEmbedStrings(prefix, withoutPrefix) }.toSet
  }
}
