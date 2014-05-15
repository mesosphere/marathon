package mesosphere.marathon.api.v2

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.Timestamp

@JsonIgnoreProperties(ignoreUnknown = true)
case class GroupUpdate(
  apps : Option[Seq[AppUpdate]],
  scalingStrategy: Option[ScalingStrategy]
) {

  def apply(group:Group) : Group = {
    var updated = group
    for (v <- scalingStrategy) updated = updated.copy(scalingStrategy=v)
    for (v <- apps) {
      def appDef(ido:Option[String]) : AppDefinition = {
        ido.fold(AppDefinition())(id => group.apps.find(_.id == id).getOrElse(AppDefinition(id)))
      }
      val updatedApps = v.map(update => update.apply(appDef(update.id)))
      val untouchedApps = updated.apps.filterNot(existing => updatedApps.exists(_.id==existing.id))
      updated = updated.copy(apps = updatedApps ++ untouchedApps)
    }
    updated.copy(version = Timestamp.now())
  }
}
