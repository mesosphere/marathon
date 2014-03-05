package mesosphere.marathon.api.v2.json

import com.fasterxml.jackson.databind._
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import mesosphere.marathon.state.Timestamp
import com.fasterxml.jackson.core.{JsonToken, JsonParser, JsonGenerator, Version}
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer


/**
 * @author Tobi Knaup
 */

class MarathonModule extends Module {

  private val constraintClass = classOf[Constraint]
  private val marathonTaskClass = classOf[MarathonTask]
  private val enrichedTaskClass = classOf[EnrichedTask]
  private val timestampClass = classOf[Timestamp]

  def getModuleName: String = "MarathonModule"

  def version(): Version = new Version(0, 0, 1, null , null , null)

  def setupModule(context: SetupContext) {
    context.addSerializers(new Serializers.Base {
      override def findSerializer(config: SerializationConfig, javaType: JavaType,
                                  beanDesc: BeanDescription): JsonSerializer[_] = {
        if (constraintClass.isAssignableFrom(javaType.getRawClass)) {
          ConstraintSerializer
        }
        else if (marathonTaskClass.isAssignableFrom(javaType.getRawClass)) {
          MarathonTaskSerializer
        }
        else if (enrichedTaskClass.isAssignableFrom(javaType.getRawClass)) {
          EnrichedTaskSerializer
        }
        else if (timestampClass.isAssignableFrom(javaType.getRawClass)) {
          TimestampSerializer
        }
        else {
          null
        }
      }
    })

    context.addDeserializers(new Deserializers.Base {
      override def findBeanDeserializer(javaType: JavaType, config: DeserializationConfig,
                                        beanDesc: BeanDescription): JsonDeserializer[_] = {
        if (constraintClass.isAssignableFrom(javaType.getRawClass)) {
          ConstraintDeserializer
        }
        else if (marathonTaskClass.isAssignableFrom(javaType.getRawClass)) {
          MarathonTaskDeserializer
        }
        else if (timestampClass.isAssignableFrom(javaType.getRawClass)) {
          TimestampDeserializer
        }
        else {
          null
        }
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
    def deserialize(json: JsonParser, context: DeserializationContext): Timestamp = {
      Timestamp(json.getText)
    }
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
      jgen.writeEndObject()
    }
  }

}
