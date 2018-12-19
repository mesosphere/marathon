package mesosphere.raml

import java.io.File
import java.net.URI
import java.nio.file.Paths

import com.github.difflib.DiffUtils
import com.github.difflib.patch.{ChangeDelta, DeleteDelta, InsertDelta}
import org.raml.v2.api.RamlModelBuilder
import org.scalatest.Matchers._
import org.scalatest.{GivenWhenThen, WordSpec}


import collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._

class RamlTypeGeneratorTest extends WordSpec with GivenWhenThen {

  "RamlTypeGenerator" should {
    "produce types" in {
      Given("A reference model")
      val file = new File("src/test/resources/test.raml")
      assert(file.exists(), "file does not exist")
      val models = Seq(new RamlModelBuilder().buildApi(file))
      models should have size(1)

      When("the type is generated")
      val types = RamlTypeGenerator(models.toVector, "mesosphere.raml.test")
      val typesAsStr: Map[String, String] = types.mapValues(treehugger.forest.treeToString(_))

      Then("the type equals our reference")
      val original = typesAsStr("Foo").lines.toList.asJava
      val refLines = scala.io.Source.fromResource("references/Foo.scala").getLines().toList.asJava
      val deltas = DiffUtils.diff(original, refLines).getDeltas.asScala
      deltas.foreach {
        case d: DeleteDelta[_] => d.getSource().getLines().foreach { l => println(s"- $l") }
        case i: InsertDelta[_] => i.getSource().getLines().foreach { l => println(s"+ $l") }
        case c: ChangeDelta[_] =>
          c.getSource().getLines().foreach { l => println(s"- $l") }
          c.getTarget().getLines().foreach { l => println(s"+ $l") }
        case e => println(e)
      }
      val ref = scala.io.Source.fromResource("references/Foo.scala").mkString
      typesAsStr("Foo") should be(ref)
    }
  }
}