package mesosphere.raml.backend.liquid

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT
import org.scalatest.{GivenWhenThen, Matchers, WordSpec}

class EnumVisitorTest extends WordSpec with GivenWhenThen with Matchers with StrictLogging {

  val expected: String =
    """// format: OFF
      |package mesosphere.marathon.raml
      |
      |sealed trait myEnum extends Product with Serializable with RamlGenerated {
      |  val value: String
      |  override def toString: String = value
      |}
      |
      |object myEnum {
      |  case object Bar extends myEnum {
      |    val value = BAR
      |  }case object Foo extends myEnum {
      |    val value = FOO
      |  }
      |  implicit object playJsonFormat extends play.api.libs.json.Format[myEnum] {
      |    def reads(json: play.api.libs.json.JsValue): play.api.libs.json.JsResult[myEnum] =  {
      |      json match {
      |        case play.api.libs.json.JsString(s) => s.toLowerCase match {
      |          case "bar" => play.api.libs.json.JsSuccess(Bar)
      |          case "foo" => play.api.libs.json.JsSuccess(Foo)
      |          case _ => play.api.libs.json.JsError(play.api.libs.json.JsonValidationError("error.unknown.enum.literal", "myEnum ([BAR, FOO])"))
      |        }
      |        case _ => play.api.libs.json.JsError(play.api.libs.json.JsonValidationError("error.unknown.enum.literal", "myEnum ([BAR, FOO])"))
      |      }
      |    }
      |    def writes(o: myEnum): play.api.libs.json.JsValue = play.api.libs.json.JsString(o.value)
      |  }
      |}
      |""".stripMargin

  "EnumVisitor" should {
    "generate the enum base trait" in {
      Given("An enum type")
      val enumT = EnumT("myEnum", Set("Foo", "Bar"), Some("Foo"), Seq("Foo's a foo", "Bar's a bar"))

      When("the typ is generated")
      val out = EnumVisitor.visit(enumT)

      Then("the source includes the base trait")
      out.mkString("/n") should be(expected)
    }
  }
}
