package mesosphere.marathon.api.v2.json

import com.fasterxml.jackson.databind._
import mesosphere.marathon.Protos.{ MarathonTask, Constraint }
import mesosphere.marathon.state.Timestamp
import mesosphere.marathon.health.HealthCheck
import com.fasterxml.jackson.core._
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.deser.Deserializers
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit.SECONDS
import mesosphere.marathon.{ EmptyContainerInfo, ContainerInfo }
import scala.collection.JavaConverters._
import mesosphere.marathon.api.v2._
import java.lang.{ Integer => JInt, Double => JDouble }
import mesosphere.marathon.api.validation.FieldConstraints._
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
  * @author Tobi Knaup
  */

class MarathonModule extends Module {
  import MarathonModule._

  private val constraintClass = classOf[Constraint]
  private val marathonTaskClass = classOf[MarathonTask]
  private val enrichedTaskClass = classOf[EnrichedTask]
  private val timestampClass = classOf[Timestamp]
  private val finiteDurationClass = classOf[FiniteDuration]
  private val containerInfoClass = classOf[ContainerInfo]
  private val appUpdateClass = classOf[AppUpdate]

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
        else if (matches(containerInfoClass)) ContainerInfoSerializer
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
        else if (matches(containerInfoClass)) ContainerInfoDeserializer
        else if (matches(appUpdateClass)) AppUpdateDeserializer
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
      builder.setOperator(Constraint.Operator.valueOf(json.getText.toUpperCase))
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
      if (enriched.healthCheckResults.nonEmpty) {
        jgen.writeObjectField("healthCheckResults", enriched.healthCheckResults)
      }
      jgen.writeEndObject()
    }
  }

  object ContainerInfoSerializer extends JsonSerializer[ContainerInfo] {
    def serialize(container: ContainerInfo, jgen: JsonGenerator, provider: SerializerProvider) {
      jgen.writeStartObject()

      container match {
        case EmptyContainerInfo => // nothing to do here
        case _ =>
          jgen.writeStringField("image", container.image)
          jgen.writeObjectField("options", container.options)
      }

      jgen.writeEndObject()
    }
  }

  object ContainerInfoDeserializer extends JsonDeserializer[ContainerInfo] {
    def deserialize(json: JsonParser, context: DeserializationContext): ContainerInfo = {
      val oc = json.getCodec
      val tree: JsonNode = oc.readTree(json)

      if (tree.has("image") && tree.has("options")) {
        ContainerInfo(
          image = tree.get("image").asText(),
          options = tree.get("options").elements().asScala.map(_.asText()).toList)
      }
      else {
        EmptyContainerInfo
      }
    }
  }

  object AppUpdateDeserializer extends JsonDeserializer[AppUpdate] {
    override def deserialize(json: JsonParser, context: DeserializationContext): AppUpdate = {
      val oc = json.getCodec
      val tree: JsonNode = oc.readTree(json)

      val containerInfo = if (tree.has("container")) {
        val container = tree.get("container")

        if (container.isNull) {
          Some(EmptyContainerInfo)
        }
        else {
          Option(ContainerInfoDeserializer.deserialize(container.traverse(oc), context))
        }
      }
      else {
        None
      }

      val appUpdate = tree.traverse(oc).readValueAs(classOf[AppUpdateBuilder])

      appUpdate.copy(container = containerInfo).build
    }
  }
}

object MarathonModule {
  // TODO: make @JsonDeserialize work on the 'container' field
  // of the 'AppUpdate' class and remove this workaround.
  @JsonIgnoreProperties(ignoreUnknown = true)
  case class AppUpdateBuilder(
      cmd: Option[String] = None,

      instances: Option[JInt] = None,

      cpus: Option[JDouble] = None,

      mem: Option[JDouble] = None,

      uris: Option[Seq[String]] = None,

      @FieldPortsArray ports: Option[Seq[JInt]] = None,

      constraints: Option[Set[Constraint]] = None,

      executor: Option[String] = None,

      container: Option[ContainerInfo] = None,

      healthChecks: Option[Set[HealthCheck]] = None,

      version: Option[Timestamp] = None) {
    def build = AppUpdate(
      cmd, instances, cpus, mem, uris, ports, constraints,
      executor, container, healthChecks, version
    )
  }

  private [this] val Percent = """^(\d+)\%$""".r

  class StepSerializer extends JsonSerializer[Step] {
    def serialize(
        stepping: Step,
        json: JsonGenerator,
        provider: SerializerProvider): Unit = stepping match {

      case AbsoluteStep(count) => json.writeNumber(count)
      case RelativeStep(factor) => json.writeString(s"${(factor * 100).toInt}%")
    }
  }

  class StepDeserializer extends JsonDeserializer[Step]{
    override def deserialize(json: JsonParser, context: DeserializationContext): Step = {
      val token = json.getCurrentToken

      if (token.isNumeric) {
        AbsoluteStep(json.getValueAsInt)
      } else {
        json.getValueAsString match {
          case Percent(x) => RelativeStep(x.toDouble / 100.0)
          case _ => throw new IllegalArgumentException("Value must be in percent.")
        }
      }
    }
  }
}
