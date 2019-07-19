package mesosphere.marathon
package raml

import mesosphere.marathon.core.appinfo.{GroupInfo => CoreGroupInfo}

trait GroupInfoConversion extends AppConversion {

  implicit val podRamlWriter: Writes[CoreGroupInfo, GroupInfo] = Writes { info =>
    GroupInfo(
      id = info.group.id.toString,
      dependencies = info.group.dependencies.map(_.toString),
      version = Some(info.group.version.toOffsetDateTime),
      enforceRole = Some(info.group.enforceRole),
      apps = info.maybeApps.fold(Set.empty)(apps => apps.toRaml)
    )
  }

  //      val maybeJson = Seq[Option[JsObject]](
  //        info.maybeApps.map(apps => Json.obj("apps" -> apps)),
  //        info.maybeGroups.map(groups => Json.obj("groups" -> groups)),
  //        info.maybePods.map(pods => Json.obj("pods" -> pods))
  //      ).flatten
  //
  //      val groupJson = Json.obj (
  //        "enforceRole" -> info.group.enforceRole
  //      )
  //
  //      maybeJson.foldLeft(groupJson)((result, obj) => result ++ obj)
}
