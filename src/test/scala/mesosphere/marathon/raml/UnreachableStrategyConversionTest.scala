package mesosphere.marathon
package raml

import mesosphere.UnitTest

import scala.concurrent.duration._

class UnreachableStrategyConversionTest extends UnitTest {

  def convertToProtobufThenToRAML(title: => String, unreachable: => state.UnreachableStrategy, raml: => UnreachableStrategy): Unit = {
    s"$title converts to protobuf, then to RAML" in {
      val proto = unreachable.toProto
      val proto2Raml = proto.toRaml
      proto2Raml should be(raml)
    }
  }

  "UnreachableStrategyConversion" should {
    "read from RAML" in {
      val raml = UnreachableEnabled()

      val result: state.UnreachableStrategy = UnreachableStrategyConversion.ramlUnreachableStrategyRead(raml)

      result shouldBe (state.UnreachableStrategy.default(resident = false))
    }
  }

  it should {
    val strategy = state.UnreachableEnabled(inactiveAfter = 10.minutes, expungeAfter = 20.minutes)

    val raml = UnreachableStrategyConversion.ramlUnreachableStrategyWrite(strategy).
      asInstanceOf[UnreachableEnabled]

    behave like convertToProtobufThenToRAML(
      "unreachable enabled",
      strategy, raml)
    behave like convertToProtobufThenToRAML(
      "unreachable disabled",
      state.UnreachableDisabled, UnreachableDisabled.DefaultValue)

    "write to RAML" in {
      raml.inactiveAfterSeconds should be(600)
      raml.expungeAfterSeconds should be(1200)
    }
  }
}
