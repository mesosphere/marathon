package mesosphere.marathon.api.v2.json

import java.lang.{ Double => JDouble, Integer => JInt }
import java.util.concurrent.TimeUnit.SECONDS

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core._
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.ser.Serializers
import org.apache.mesos.{ Protos => mesos }
import mesosphere.marathon.Protos.{ Constraint, MarathonTask }
import mesosphere.marathon.api.v2._
import mesosphere.marathon.api.validation.FieldConstraints._
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ Container, PathId, Timestamp, UpgradeStrategy }

import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration

class MarathonModule extends Module {
  import mesosphere.marathon.api.v2.json.MarathonModule._

  private val constraintClass = classOf[Constraint]
  private val marathonTaskClass = classOf[MarathonTask]
  private val enrichedTaskClass = classOf[EnrichedTask]
  private val timestampClass = classOf[Timestamp]
  private val finiteDurationClass = classOf[FiniteDuration]
  private val appUpdateClass = classOf[AppUpdate]
  private val groupIdClass = classOf[PathId]
  private val taskIdClass = classOf[mesos.TaskID]

  def getModuleName: String = "MarathonModule"

  def version(): Version = new Version(0, 0, 1, null, null, null)

  def setupModule(context: SetupContext) {
    context.addSerializers(new Serializers.Base {
      override def findSerializer(config: SerializationConfig, javaType: JavaType,
                                  beanDesc: BeanDescription): JsonSerializer[_] = {

        def matches(clazz: Class[_]): Boolean = clazz isAssignableFrom javaType.getRawClass

        if (matches(constraintClass)) ConstraintSerializer
        else if (matches(marathonTaskClass)) MarathonTaskSerializer
        else if (matches(enrichedTaskClass)) EnrichedTaskSerializer
        else if (matches(timestampClass)) TimestampSerializer
        else if (matches(finiteDurationClass)) FiniteDurationSerializer
        else if (matches(groupIdClass)) PathIdSerializer
        else if (matches(taskIdClass)) TaskIdSerializer
        else null
      }
    })

    context.addDeserializers(new Deserializers.Base {
      override def findBeanDeserializer(javaType: JavaType, config: DeserializationConfig,
                                        beanDesc: BeanDescription): JsonDeserializer[_] = {

        def matches(clazz: Class[_]): Boolean = clazz isAssignableFrom javaType.getRawClass

        if (matches(constraintClass)) ConstraintDeserializer
        else if (matches(marathonTaskClass)) MarathonTaskDeserializer
        else if (matches(timestampClass)) TimestampDeserializer
        else if (matches(finiteDurationClass)) FiniteDurationDeserializer
        else if (matches(appUpdateClass)) AppUpdateDeserializer
        else if (matches(groupIdClass)) PathIdDeserializer
        else if (matches(taskIdClass)) TaskIdDeserializer
        else null
      }
    })
  }

  object ConstraintSerializer extends JsonSerializer[Constraint] {
    def serialize(constraint: Constraint, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeStartArray()
      jgen.writeString(constraint.getField)
      jgen.writeString(constraint.getOperator.toString)
      if (constraint.hasValue) {
        jgen.writeString(constraint.getValue)
      }
      jgen.writeEndArray()
    }
  }

  object ConstraintDeserializer extends JsonDeserializer[Constraint] {
    def deserialize(json: JsonParser, context: DeserializationContext): Constraint = {
      val builder = Constraint.newBuilder
      json.nextToken() // skip [
      builder.setField(json.getText)
      json.nextToken()

      val operatorString = json.getText.toUpperCase
      try {
        builder.setOperator(Constraint.Operator.valueOf(operatorString))
      }
      catch {
        case e: IllegalArgumentException =>
          throw new JsonParseException(s"Invalid operator: '$operatorString'", json.getCurrentLocation)
      }
      json.nextToken()

      if (json.getCurrentToken == JsonToken.VALUE_STRING) {
        builder.setValue(json.getText)
        json.nextToken()
      }
      builder.build
    }
  }

  object TimestampSerializer extends JsonSerializer[Timestamp] {
    def serialize(ts: Timestamp, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeString(ts.toString)
    }
  }

  object TimestampDeserializer extends JsonDeserializer[Timestamp] {
    def deserialize(json: JsonParser, context: DeserializationContext): Timestamp =
      Timestamp(json.getText)
  }

  // Note: loses sub-second resolution
  object FiniteDurationSerializer extends JsonSerializer[FiniteDuration] {
    def serialize(fd: FiniteDuration, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeNumber(fd.toSeconds)
    }
  }

  // Note: loses sub-second resolution
  object FiniteDurationDeserializer extends JsonDeserializer[FiniteDuration] {
    def deserialize(json: JsonParser, context: DeserializationContext): FiniteDuration =
      FiniteDuration(json.getLongValue, SECONDS)
  }

  object MarathonTaskSerializer extends JsonSerializer[MarathonTask] {
    def serialize(task: MarathonTask, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeStartObject()
      writeFieldValues(task, jgen, provider)
      jgen.writeEndObject()
    }

    def writeFieldValues(task: MarathonTask, jgen: JsonGenerator, provider: SerializerProvider) {
      val startedAt = task.getStartedAt
      val stagedAt = task.getStagedAt
      jgen.writeObjectField("id", task.getId)
      jgen.writeObjectField("host", task.getHost)
      jgen.writeObjectField("ports", task.getPortsList)
      jgen.writeObjectField("startedAt", if (startedAt == 0) null else Timestamp(startedAt))
      jgen.writeObjectField("stagedAt", if (stagedAt == 0) null else Timestamp(stagedAt))
      jgen.writeObjectField("version", task.getVersion)
    }
  }

  // TODO: handle fields!
  // Currently there is no support for handling updates to task instances (CD)
  object MarathonTaskDeserializer extends JsonDeserializer[MarathonTask] {
    def deserialize(json: JsonParser, context: DeserializationContext): MarathonTask = {
      MarathonTask.newBuilder.build
    }
  }

  object EnrichedTaskSerializer extends JsonSerializer[EnrichedTask] {
    def serialize(enriched: EnrichedTask, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeStartObject()
      jgen.writeObjectField("appId", enriched.appId)
      MarathonTaskSerializer.writeFieldValues(enriched.task, jgen, provider)
      if (enriched.servicePorts.nonEmpty) {
        jgen.writeObjectField("servicePorts", enriched.servicePorts)
      }
      if (enriched.healthCheckResults.nonEmpty) {
        jgen.writeObjectField("healthCheckResults", enriched.healthCheckResults)
      }
      jgen.writeEndObject()
    }
  }

  object PathIdSerializer extends JsonSerializer[PathId] {
    def serialize(id: PathId, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeString(id.toString)
    }
  }

  object PathIdDeserializer extends JsonDeserializer[PathId] {
    def deserialize(json: JsonParser, context: DeserializationContext): PathId = {
      val tree: JsonNode = json.getCodec.readTree(json)
      tree.textValue().toPath
    }
  }

  object TaskIdSerializer extends JsonSerializer[mesos.TaskID] {
    def serialize(id: mesos.TaskID, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeString(id.getValue)
    }
  }

  object TaskIdDeserializer extends JsonDeserializer[mesos.TaskID] {
    def deserialize(json: JsonParser, context: DeserializationContext): mesos.TaskID = {
      val tree: JsonNode = json.getCodec.readTree(json)
      mesos.TaskID.newBuilder.setValue(tree.textValue).build
    }
  }

  object AppUpdateDeserializer extends JsonDeserializer[AppUpdate] {
    override def deserialize(json: JsonParser, context: DeserializationContext): AppUpdate = {
      val oc = json.getCodec
      val tree: JsonNode = oc.readTree(json)
      val containerDeserializer = context.findRootValueDeserializer(
        context.constructType(classOf[Container])
      ).asInstanceOf[JsonDeserializer[Container]]

      val emptyContainer = tree.has("container") && tree.get("container").isNull

      val appUpdate =
        tree.traverse(oc).readValueAs(classOf[AppUpdateBuilder]).build

      if (emptyContainer)
        appUpdate.copy(container = Some(Container.Empty))
      else appUpdate
    }
  }
}

object MarathonModule {

  // TODO: make @JsonDeserialize work on the 'container' field
  // of the 'AppUpdate' class and remove this workaround.
  @JsonIgnoreProperties(ignoreUnknown = true)
  case class AppUpdateBuilder(
      id: Option[PathId] = None, //needed for updates inside a group
      cmd: Option[String] = None,
      args: Option[Seq[String]] = None,
      user: Option[String] = None,
      env: Option[Map[String, String]] = None,
      instances: Option[JInt] = None,
      cpus: Option[JDouble] = None,
      mem: Option[JDouble] = None,
      disk: Option[JDouble] = None,
      executor: Option[String] = None,
      constraints: Option[Set[Constraint]] = None,
      uris: Option[Seq[String]] = None,
      storeUrls: Option[Seq[String]] = None,
      @FieldPortsArray ports: Option[Seq[JInt]] = None,
      requirePorts: Option[Boolean] = None,
      @FieldJsonProperty("backoffSeconds") backoff: Option[FiniteDuration] = None,
      backoffFactor: Option[JDouble] = None,
      container: Option[Container] = None,
      healthChecks: Option[Set[HealthCheck]] = None,
      dependencies: Option[Set[PathId]] = None,
      upgradeStrategy: Option[UpgradeStrategy] = None,
      version: Option[Timestamp] = None) {
    def build(): AppUpdate = AppUpdate(
      id, cmd, args, user, env, instances, cpus, mem, disk, executor, constraints,
      uris, storeUrls, ports, requirePorts, backoff, backoffFactor, container, healthChecks,
      dependencies, upgradeStrategy, version
    )
  }

}
