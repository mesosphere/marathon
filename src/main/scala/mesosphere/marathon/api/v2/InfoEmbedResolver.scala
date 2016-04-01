package mesosphere.marathon.api.v2

import mesosphere.marathon.core.appinfo.{ GroupInfo, AppInfo }
import org.slf4j.LoggerFactory

/**
  * Resolves AppInfo.Embed and GroupInfo.Embed from query parameters.
  */
private[v2] object InfoEmbedResolver {
  private[this] val log = LoggerFactory.getLogger(getClass)

  private[this] val EmbedAppsPrefixes = Set("group.apps.", "apps.", "app.")

  private[this] val EmbedTasks = "tasks"
  private[this] val EmbedDeployments = "deployments"
  private[this] val EmbedReadiness = "readiness"

  /* deprecated, use lastTaskFailure, tasks, deployments instead */
  private[this] val EmbedTasksAndFailures = "failures"
  private[this] val EmbedLastTaskFailure = "lastTaskFailure"
  private[this] val EmbedCounts = "counts"
  private[this] val EmbedTaskStats = "taskStats"

  private[v2] val EmbedGroups = "group.groups"
  private[v2] val EmbedApps = "group.apps"

  /**
    * Converts embed arguments to our internal representation.
    *
    * Accepts the arguments with all prefixes or even no prefix at all
    * to avoid subtle user errors confusing the two.
    */
  def resolveApp(embed: Set[String]): Set[AppInfo.Embed] = {
    def mapEmbedStrings(prefix: String, withoutPrefix: String): Set[AppInfo.Embed] = withoutPrefix match {
      case EmbedTasks => Set(AppInfo.Embed.Tasks, /* deprecated */ AppInfo.Embed.Deployments)
      case EmbedTasksAndFailures =>
        log.warn(s"Using deprecated embed=s$prefix$withoutPrefix. " +
          s"Use ${prefix}tasks, ${prefix}lastTaskFailure, ${prefix}deployments instead.")
        Set(AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments)
      case EmbedDeployments     => Set(AppInfo.Embed.Deployments)
      case EmbedReadiness       => Set(AppInfo.Embed.Readiness)
      case EmbedLastTaskFailure => Set(AppInfo.Embed.LastTaskFailure)
      case EmbedCounts          => Set(AppInfo.Embed.Counts)
      case EmbedTaskStats       => Set(AppInfo.Embed.TaskStats)
      case unknown: String =>
        log.warn(s"unknown app embed argument: $prefix$unknown")
        Set.empty
    }

    def separatePrefix(embedMe: String): (String, String) = {
      val removablePrefix = EmbedAppsPrefixes.find(embedMe.startsWith).getOrElse("")
      val withoutPrefix = embedMe.substring(removablePrefix.length)
      (removablePrefix, withoutPrefix)
    }

    val embedWithSeparatedPrefixes = embed.map(separatePrefix)
    embedWithSeparatedPrefixes.flatMap { case (prefix, withoutPrefix) => mapEmbedStrings(prefix, withoutPrefix) }
  }

  def resolveGroup(embeds: Set[String]): Set[GroupInfo.Embed] = {
    embeds.flatMap {
      case EmbedGroups => Some(GroupInfo.Embed.Groups)
      case EmbedApps   => Some(GroupInfo.Embed.Apps)
      case unknown: String =>
        log.warn(s"unknown group embed argument: $unknown")
        None
    }
  }

  /**
    * Resolve apps and groups embed parameter into distinct sets.
    */
  def resolveAppGroup(embeds: Set[String]): (Set[AppInfo.Embed], Set[GroupInfo.Embed]) = {
    val (apps, groups) = embeds.partition(_.startsWith("group.apps."))
    (resolveApp(apps), resolveGroup(groups))
  }
}
