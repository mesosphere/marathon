package mesosphere.marathon
package json

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import play.api.libs.json.{JsValue, Json}

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(1)
class JsonSerializeBenchmark extends JsonSerializeDeserializeState {

  @Benchmark
  def jsonSerialiseWrite(hole: Blackhole): Unit = {
    val value: JsValue = Json.toJson[raml.GroupUpdate](groupMock)
    val str: String = value.toString()
    hole.consume(str)
  }

  @Benchmark
  def jsonSerialiseWriteJackson(hole: Blackhole): Unit = {
    val output = jacksonSerializer.writeValueAsString(groupMock)
    hole.consume(output)
  }

}
