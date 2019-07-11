package mesosphere.marathon
package core.appinfo

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import mesosphere.marathon.api.v2.json.JacksonSerializable
import mesosphere.marathon.raml.PodStatus
import mesosphere.marathon.state.{Group, RootGroup}

case class GroupInfo(
    group: Group,
    maybeApps: Option[Seq[AppInfo]],
    maybePods: Option[Seq[PodStatus]],
    maybeGroups: Option[Seq[GroupInfo]]) extends JacksonSerializable[GroupInfo] {

  def transitiveApps: Option[Seq[AppInfo]] = this.maybeApps.map { apps =>
    apps ++ maybeGroups.map { _.flatMap(_.transitiveApps.getOrElse(Seq.empty)) }.getOrElse(Seq.empty)
  }
  def transitiveGroups: Option[Seq[GroupInfo]] = this.maybeGroups.map { groups =>
    groups ++ maybeGroups.map { _.flatMap(_.transitiveGroups.getOrElse(Seq.empty)) }.getOrElse(Seq.empty)
  }

  override def serializeWithJackson(gen: JsonGenerator, provider: SerializerProvider): Unit = {
    gen.writeStartObject()
    gen.writeObjectField("id", group.id)
    gen.writeObjectField("dependencies", group.dependencies)
    gen.writeObjectField("version", group.version)

    maybeApps.foreach(apps => {
      gen.writeArrayFieldStart("apps")
      apps.foreach(gen.writeObject)
      gen.writeEndArray()
    })
    maybeGroups.foreach(groups => {
      gen.writeArrayFieldStart("groups")
      groups.foreach(gen.writeObject)
      gen.writeEndArray()
    })
    maybePods.foreach(pods => {
      gen.writeArrayFieldStart("pods")
      pods.foreach(gen.writeObject)
      gen.writeEndArray()
    })
    gen.writeEndObject()
  }
}

object GroupInfo {
  sealed trait Embed
  object Embed {
    case object Groups extends Embed
    case object Apps extends Embed
    case object Pods extends Embed
  }
  lazy val empty: GroupInfo = GroupInfo(RootGroup.empty, None, None, None)

}

