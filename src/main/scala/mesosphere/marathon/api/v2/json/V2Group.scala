package mesosphere.marathon.api.v2.json

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.state.{ Group, PathId, Timestamp }
import mesosphere.marathon.api.v2.Validation._

case class V2Group(
    id: PathId,
    apps: Set[V2AppDefinition] = V2Group.defaultApps,
    groups: Set[V2Group] = V2Group.defaultGroups,
    dependencies: Set[PathId] = Group.defaultDependencies,
    version: Timestamp = Group.defaultVersion) {

  /**
    * Returns the canonical internal representation of this API-specific
    * group.
    */
  def toGroup(): Group =
    Group(
      id = id,
      apps = apps.map(_.toAppDefinition),
      groups = groups.map(_.toGroup),
      dependencies = dependencies,
      version = version
    )

}

object V2Group {

  val defaultApps: Set[V2AppDefinition] = Set.empty
  val defaultGroups: Set[V2Group] = Set.empty

  def apply(group: Group): V2Group =
    V2Group(
      id = group.id,
      apps = group.apps.map(V2AppDefinition(_)),
      groups = group.groups.map(V2Group(_)),
      dependencies = group.dependencies,
      version = group.version)

  implicit val v2GroupValidator: Validator[V2Group] = validator[V2Group] { group =>
    group.id is valid
    group.apps is valid
    group.groups is valid
    group is noAppsAndGroupsWithSameName
    (group.id.isRoot is false) or (group.dependencies is noCyclicDependencies(group))

    group is validPorts
  }

  def v2GroupWithConfigValidator(maxApps: Option[Int])(implicit validator: Validator[V2Group]): Validator[V2Group] = {
    new Validator[V2Group] {
      override def apply(group: V2Group): Result = {
        maxApps.filter(group.toGroup().transitiveApps.size > _).map { num =>
          Failure(Set(RuleViolation(group,
            s"""This Marathon instance may only handle up to $num Apps!
                |(Override with command line option --max_apps)""".stripMargin, None)))
        } getOrElse Success
      } and validator(group)
    }
  }

  private def noAppsAndGroupsWithSameName: Validator[V2Group] =
    new Validator[V2Group] {
      def apply(group: V2Group) = {
        val groupIds = group.groups.map(_.id)
        val clashingIds = group.apps.map(_.id).intersect(groupIds)

        if (clashingIds.isEmpty) Success
        else Failure(Set(RuleViolation(group,
          s"Groups and Applications may not have the same identifier: ${clashingIds.mkString(", ")}", None)))
      }
    }

  private def noCyclicDependencies(group: V2Group): Validator[Set[PathId]] =
    new Validator[Set[PathId]] {
      def apply(dependencies: Set[PathId]) = {
        if (group.toGroup().hasNonCyclicDependencies) Success
        else Failure(Set(RuleViolation(group, "Dependency graph has cyclic dependencies", None)))
      }
    }

  private def validPorts: Validator[V2Group] = {
    new Validator[V2Group] {
      override def apply(group: V2Group): Result = {
        val groupViolations = group.apps.flatMap { app =>
          val ruleViolations = app.toAppDefinition.containerServicePorts.toSeq.flatMap { servicePorts =>
            for {
              existingApp <- group.toGroup().transitiveApps.toList
              if existingApp.id != app.id // in case of an update, do not compare the app against itself
              existingServicePort <- existingApp.portMappings.toList.flatten.map(_.servicePort)
              if existingServicePort != 0 // ignore zero ports, which will be chosen at random
              if servicePorts contains existingServicePort
            } yield RuleViolation(app.id,
              s"Requested service port $existingServicePort conflicts with a service port in app ${existingApp.id}",
              None)
          }

          if (ruleViolations.isEmpty) None
          else Some(GroupViolation(app, "app contains conflicting ports", None, ruleViolations.toSet))
        }

        if (groupViolations.isEmpty) Success
        else Failure(groupViolations.toSet)
      }
    }
  }
}
