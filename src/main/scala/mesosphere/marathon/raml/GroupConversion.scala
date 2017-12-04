package mesosphere.marathon
package raml

import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, Group => CoreGroup, VersionInfo => CoreVersionInfo }

trait GroupConversion {

  // TODO needs a dedicated/focused unit test; other (larger) unit tests provide indirect coverage
  implicit val groupUpdateRamlReads: Reads[(UpdateGroupStructureOp, App => AppDefinition), CoreGroup] =
    Reads[(UpdateGroupStructureOp, App => AppDefinition), CoreGroup] {
      case (op, cf) =>
        op.apply(cf)
    }

  implicit val groupWritesRaml: Writes[CoreGroup, Group] =
    Writes[CoreGroup, Group] { group =>
      Group(
        id = group.id.toString,
        apps = group.apps.map { case (_, app) => Raml.toRaml(app) }(collection.breakOut),
        pods = group.pods.map { case (_, pod) => Raml.toRaml(pod) }(collection.breakOut),
        groups = group.groupsById.map { case (_, g) => Raml.toRaml(g) }(collection.breakOut),
        dependencies = group.dependencies.map(_.toString),
        version = Some(group.version.toOffsetDateTime)
      )
    }
}

object GroupConversion extends GroupConversion {
  def apply(groupUpdate: GroupUpdate, current: CoreGroup, timestamp: Timestamp): UpdateGroupStructureOp =
    UpdateGroupStructureOp(groupUpdate, current, timestamp)
}

case class UpdateGroupStructureOp(
    groupUpdate: GroupUpdate,
    current: CoreGroup,
    timestamp: Timestamp
) {
  def apply(implicit cf: App => AppDefinition): CoreGroup = UpdateGroupStructureOp.execute(groupUpdate, current, timestamp)
}

object UpdateGroupStructureOp {

  import Normalization._

  private def requireGroupPath(groupUpdate: GroupUpdate)(implicit normalPaths: Normalization[PathId]): PathId = {
    groupUpdate.id.map(PathId(_).normalize).getOrElse(
      // validation should catch this..
      throw SerializationFailedException("No group id was given!")
    )
  }

  private def normalizeApp(version: Timestamp)(implicit normalPaths: Normalization[PathId]): Normalization[AppDefinition] = Normalization { app =>
    app.copy(id = app.id.normalize, dependencies = app.dependencies.map(_.normalize),
      versionInfo = CoreVersionInfo.OnlyVersion(version))
  }

  /**
    * Creates a new [[state.Group]] from a [[GroupUpdate]], performing both normalization and conversion.
    */
  private def createGroup(groupUpdate: GroupUpdate, gid: PathId, version: Timestamp)(implicit cf: App => AppDefinition): CoreGroup = {
    implicit val pathNormalization: Normalization[PathId] = Normalization(_.canonicalPath(gid))
    implicit val appNormalization = normalizeApp(version)

    val appsById: Map[AppDefinition.AppKey, AppDefinition] = groupUpdate.apps.getOrElse(Set.empty).map { currentApp =>
      val app = cf(currentApp).normalize
      app.id -> app
    }(collection.breakOut)

    val groupsById: Map[PathId, CoreGroup] = groupUpdate.groups.getOrElse(Seq.empty).map { currentGroup =>
      // TODO: tailrec needed
      val group = createGroup(currentGroup, requireGroupPath(currentGroup), version)
      group.id -> group
    }(collection.breakOut)

    CoreGroup(
      id = gid,
      apps = appsById,
      pods = Map.empty,
      groupsById = groupsById,
      dependencies = groupUpdate.dependencies.fold(Set.empty[PathId])(_.map(PathId(_).normalize)),
      version = version
    )
  }

  /**
    * Main entrypoint for a group structure update operation.
    * Implements create-or-update-or-delete for a group tree or subtree.
    * Performs both normalization and conversion from RAML model to state model.
    */
  private def execute(
    groupUpdate: GroupUpdate,
    current: CoreGroup,
    timestamp: Timestamp)(implicit cf: App => AppDefinition): CoreGroup = {

    require(groupUpdate.scaleBy.isEmpty, "For a structural update, no scale should be given.")
    require(groupUpdate.version.isEmpty, "For a structural update, no version should be given.")

    implicit val pathNormalization: Normalization[PathId] = Normalization(_.canonicalPath(current.id))
    implicit val appNormalization = normalizeApp(timestamp)

    val effectiveGroups: Map[PathId, CoreGroup] = groupUpdate.groups.fold(current.groupsById) { updates =>
      updates.map { groupUpdate =>
        val gid = requireGroupPath(groupUpdate)
        val newGroup = current.groupsById.get(gid).map { group =>
          execute(groupUpdate, group, timestamp) // TODO: tailrec needed
        }.getOrElse(createGroup(groupUpdate, gid, timestamp))

        newGroup.id -> newGroup
      }(collection.breakOut)
    }

    val effectiveApps: Map[AppDefinition.AppKey, AppDefinition] = {
      groupUpdate.apps.map(_.map(cf)).getOrElse(current.apps.values).map { currentApp =>
        val app = currentApp.normalize
        app.id -> app
      }(collection.breakOut)
    }

    val effectiveDependencies = groupUpdate.dependencies.fold(current.dependencies)(_.map(PathId(_).normalize))

    CoreGroup(
      id = current.id,
      apps = effectiveApps,
      pods = current.pods,
      groupsById = effectiveGroups,
      dependencies = effectiveDependencies,
      version = timestamp)
  }
}

