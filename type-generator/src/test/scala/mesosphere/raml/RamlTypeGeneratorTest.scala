package mesosphere.raml

import java.io.File

import org.raml.v2.api.RamlModelBuilder
import org.scalatest.Matchers._
import org.scalatest.{GivenWhenThen, WordSpec}

class RamlTypeGeneratorTest extends WordSpec with GivenWhenThen {

  "RamlTypeGenerator" should {
    "produce types" in {
      Given("A reference model")
      val file = new File("src/test/resrouces/Test.raml")
      val models = Seq(new RamlModelBuilder().buildApi(file))

      When("the type is generated")
      val types = RamlTypeGenerator(models.toVector, "mesosphere.raml.test")
      val typesAsStr: Int = types.mapValues(treehugger.forest.treeToString(_))

      Then("the type equals our reference")
      typesAsStr should be("class Test")
    }
  }
}