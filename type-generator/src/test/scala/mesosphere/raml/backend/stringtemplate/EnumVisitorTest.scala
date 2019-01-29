package mesosphere.raml.backend.stringtemplate

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.EnumT
import org.scalatest.{GivenWhenThen, Matchers, WordSpec}

class EnumVisitorTest extends WordSpec with GivenWhenThen with Matchers with StrictLogging {

  "EnumVisitor" should {
    "generate the enum base trait" in {
      Given("An enum type")
      val enumT = EnumT("myEnum", Set("Foo", "Bar"), Some("Foo"), Seq("Foo's a foo", "Bar's a bar"))

      When("the typ is generated")
      val out = EnumVisitor.visit(enumT)

      Then("the source includes the base trait")
      out.mkString("/n") should be("hello")
    }
  }
}
