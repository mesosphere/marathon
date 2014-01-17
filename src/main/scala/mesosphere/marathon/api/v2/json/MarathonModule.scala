package mesosphere.marathon.api.v2.json

import com.fasterxml.jackson.databind._
import mesosphere.marathon.Protos.{MarathonTask, Constraint}
import com.fasterxml.jackson.core.{JsonToken, JsonParser, JsonGenerator, Version}
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind.ser.Serializers
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import java.text.SimpleDateFormat
import java.util.{Date, TimeZone}

/**
 * @author Tobi Knaup
 */

class MarathonModule extends Module {

  private val constraintClass = classOf[Constraint]
  private val marathonTaskClass = classOf[MarathonTask]

  def getModuleName: String = "MarathonModule"

  def version(): Version = new Version(0, 0, 1, null , null , null)

  def setupModule(context: SetupContext) {
    context.addSerializers(new Serializers.Base {
      override def findSerializer(config: SerializationConfig, javaType: JavaType,
                                  beanDesc: BeanDescription): JsonSerializer[_] = {
        if (constraintClass.isAssignableFrom(javaType.getRawClass)) {
          new ConstraintSerializer
        } else if (marathonTaskClass.isAssignableFrom(javaType.getRawClass)) {
          new MarathonTaskSerializer
        } else {
          null
        }
      }
    })

    context.addDeserializers(new Deserializers.Base {
      override def findBeanDeserializer(javaType: JavaType, config: DeserializationConfig,
                                        beanDesc: BeanDescription): JsonDeserializer[_] = {
        if (constraintClass.isAssignableFrom(javaType.getRawClass)) {
          new ConstraintDeserializer
        }
        else if (marathonTaskClass.isAssignableFrom(javaType.getRawClass)) {
          new MarathonTaskDeserializer
        }
        else {
          null
        }
      }
    })
  }

  class ConstraintSerializer extends JsonSerializer[Constraint] {
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

  class ConstraintDeserializer extends JsonDeserializer[Constraint] {
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

  val isoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
  isoDateFormat setTimeZone TimeZone.getTimeZone("UTC")

  def timestampToUTC(timestamp: Long): String = isoDateFormat.format(new Date(timestamp))

  class MarathonTaskSerializer extends JsonSerializer[MarathonTask] {
    def serialize(task: MarathonTask, jgen: JsonGenerator, provider: SerializerProvider) {

      val startedAt = task.getStartedAt
      val stagedAt = task.getStagedAt

      jgen.writeStartObject()
      jgen.writeObjectField("id", task.getId)
      jgen.writeObjectField("host", task.getHost)
      jgen.writeObjectField("ports", task.getPortsList)
      jgen.writeObjectField("startedAt", if (startedAt == 0) null else timestampToUTC(startedAt))
      jgen.writeObjectField("stagedAt", if (stagedAt == 0) null else timestampToUTC(stagedAt))
      jgen.writeEndObject()
    }
  }

  // TODO: handle fields!
  // Currently there is no support for handling updates to task instances (CD)
  class MarathonTaskDeserializer extends JsonDeserializer[MarathonTask] {
    def deserialize(json: JsonParser, context: DeserializationContext): MarathonTask = {
      MarathonTask.newBuilder.build
    }
  }

}
