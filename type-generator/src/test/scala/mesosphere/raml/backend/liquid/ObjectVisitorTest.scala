package mesosphere.raml.backend.liquid

import treehugger.forest.definitions.StringClass

import com.typesafe.scalalogging.StrictLogging
import mesosphere.raml.ir.{FieldT, ObjectT}
import org.scalatest.{GivenWhenThen, Matchers, WordSpec}

class ObjectVisitorTest extends WordSpec with GivenWhenThen with Matchers with StrictLogging {

  "ObjectVisitor" should {
    "generate the case class" in {
      Given("An object type")
      val fields = Seq(FieldT("value", StringClass.toType, Seq.empty, Seq.empty, true, None))
      val objectT = ObjectT("CommandCheck", fields, None, Seq.empty)

      When("the typ is generated")
      val out = ObjectVisitor.visit(objectT)

      Then("the source includes the base trait")
      out.mkString("/n") should be("hello")
    }
  }
}
