package mesosphere.marathon
package json

import java.util.concurrent.TimeUnit

import com.fasterxml.jackson.databind.ObjectMapper
import mesosphere.marathon.raml.{GroupConversion, Raml, RamlSerializer}
import mesosphere.marathon.state.{AppDefinition, Group, RootGroup, Timestamp}
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import play.api.libs.json.{JsValue, Json}

@State(Scope.Benchmark)
class JsonSerializeDeserializeState {

  @Param(value = Array("real/155_100.json", "real/155_200.json", "real/155_500.json", "real/155_800.json", "real/155_1000.json"))
  var jsonMockFile: String = _

  /**
    * The contents of the JSON mock file as string (for de-serialisation)
    */
  lazy val jsonMockContents: String = {
    import java.io.InputStream
    val is: InputStream = getClass.getResourceAsStream(s"/mocks/json/${jsonMockFile}")
    scala.io.Source.fromInputStream(is).mkString
  }

  /**
    * The contents of the JSON mock file as a de-serialised RAML object (for serialisation)
    */
  lazy val groupMock: raml.GroupUpdate = {
    val value: JsValue = Json.parse(jsonMockContents)
    Json.fromJson[raml.GroupUpdate](value).get
  }

  /**
    * The contents of the JSON mock file as an updatable root group
    */
  lazy val rootGroupMock: Group = {
    val value: JsValue = Json.parse(jsonMockContents)
    val groupUpdate: raml.GroupUpdate = Json.fromJson[raml.GroupUpdate](value).get

    val group: RootGroup = RootGroup.empty()
    val appConversionFunc: (raml.App => AppDefinition) = Raml.fromRaml[raml.App, AppDefinition]

    GroupConversion(groupUpdate, group, Timestamp.zero).apply(appConversionFunc)
  }

  val jacksonSerializer: ObjectMapper = RamlSerializer.serializer

}

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
class JsonSerializeDeserializeBenchmark extends JsonSerializeDeserializeState {

  @Benchmark
  def jsonParse(hole: Blackhole): Unit = {
    val value: JsValue = Json.parse(jsonMockContents)
    hole.consume(value)
  }

  @Benchmark
  def jsonParseDeserialise(hole: Blackhole): Unit = {
    val value: JsValue = Json.parse(jsonMockContents)
    val groupUpdate: raml.GroupUpdate = Json.fromJson[raml.GroupUpdate](value).get
    hole.consume(groupUpdate)
  }

  @Benchmark
  def jsonParseDeserialiseUpdate(hole: Blackhole): Unit = {
    val value: JsValue = Json.parse(jsonMockContents)
    val groupUpdate: raml.GroupUpdate = Json.fromJson[raml.GroupUpdate](value).get

    val appConversionFunc: raml.App => AppDefinition = Raml.fromRaml[raml.App, AppDefinition]
    val updatedGroup: Group = GroupConversion(groupUpdate, rootGroupMock, Timestamp.now()).apply(appConversionFunc)

    hole.consume(updatedGroup)
  }

  @Benchmark
  def jsonDeserialise(hole: Blackhole): Unit = {
    val value: JsValue = Json.toJson[raml.GroupUpdate](groupMock)
    hole.consume(value)
  }

  @Benchmark
  def jsonDeserialiseWrite(hole: Blackhole): Unit = {
    val value: JsValue = Json.toJson[raml.GroupUpdate](groupMock)
    val str: String = value.toString()
    hole.consume(str)
  }

}
