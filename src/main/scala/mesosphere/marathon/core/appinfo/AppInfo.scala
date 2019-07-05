package mesosphere.marathon
package core.appinfo

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import mesosphere.marathon.api.v2.json.JacksonSerializable
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.raml.{AppSerializer, Raml, TaskConversion, TaskCountsSerializer}
import mesosphere.marathon.state.{AppDefinition, Identifiable, TaskFailure}

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
    maybeTaskStats: Option[TaskStatsByVersion] = None) extends JacksonSerializable[AppInfo] {

  override def serializeWithJackson(value: AppInfo, gen: JsonGenerator, provider: SerializerProvider): Unit = {
    gen.writeStartObject()

    implicit val taskConversion = TaskConversion.enrichedTaskRamlWrite
    implicit val taskCountsConversion = TaskConversion.taskCountsWrite

    AppSerializer.serializeFields(Raml.toRaml(value.app), gen, provider)
    maybeCounts.foreach(counts => TaskCountsSerializer.serializeFields(Raml.toRaml(counts), gen, provider))

    maybeDeployments.foreach(gen.writeObjectField("deployments", _))
    maybeReadinessCheckResults.foreach(gen.writeObjectField("readinessCheckResults", _))
    maybeTasks.foreach(tasks => gen.writeObjectField("tasks", Raml.toRaml(tasks)))
    maybeLastTaskFailure.foreach(gen.writeObjectField("lastTaskFailure", _))
    maybeTaskStats.foreach(gen.writeObjectField("taskStats", _))

    gen.writeEndObject()

    //    val appJson = RunSpecWrites.writes(info.app).as[JsObject]
    //
    //    val maybeJson = Seq[Option[JsObject]](
    //      info.maybeCounts.map(TaskCountsWrites.writes(_).as[JsObject]),
    //      info.maybeDeployments.map(deployments => Json.obj("deployments" -> deployments)),
    //      info.maybeReadinessCheckResults.map(readiness => Json.obj("readinessCheckResults" -> readiness)),
    //      info.maybeTasks.map(tasks => Json.obj("tasks" -> Raml.toRaml(tasks))),
    //      info.maybeLastTaskFailure.map(lastFailure => Json.obj("lastTaskFailure" -> lastFailure)),
    //      info.maybeTaskStats.map(taskStats => Json.obj("taskStats" -> taskStats))
    //    ).flatten
    //
    //    maybeJson.foldLeft(appJson)((result, obj) => result ++ obj)
  }
}

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
