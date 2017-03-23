package mesosphere.marathon
package raml

import mesosphere.marathon.state.{ AppDefinition, PathId, Timestamp, Group => CoreGroup }
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state.PathId._

trait GroupConversionTesting {

  implicit val groupRamlReads: Reads[Group, CoreGroup] = Reads { group =>
    val appsById: Map[AppDefinition.AppKey, AppDefinition] = group.apps.map { ramlApp =>
      val app: AppDefinition = Raml.fromRaml(ramlApp) // assume that we only generate canonical app json
      app.id -> app
    }(collection.breakOut)
    val groupsById: Map[PathId, CoreGroup] = group.groups.map { ramlGroup =>
      val grp: CoreGroup = Raml.fromRaml(ramlGroup)
      grp.id -> grp
    }(collection.breakOut)
    CoreGroup(
      id = group.id.toPath,
      apps = appsById,
      pods = Map.empty[PathId, PodDefinition], // we never read in pods
      groupsById = groupsById,
      dependencies = group.dependencies.map(_.toPath),
      version = group.version.map(Timestamp(_)).getOrElse(Timestamp.now()),
      transitiveAppsById = appsById ++ groupsById.values.flatMap(_.transitiveAppsById),
      transitivePodsById = groupsById.values.flatMap(_.transitivePodsById)(collection.breakOut)
    )
  }
}

object GroupConversionTesting extends GroupConversionTesting
