package mesosphere.raml

import java.io.File
import java.net.URI
import java.nio.file.Paths

import com.github.difflib.DiffUtils
import com.github.difflib.patch.{AbstractDelta, ChangeDelta, DeleteDelta, InsertDelta}
import org.raml.v2.api.RamlModelBuilder
import org.scalatest.Matchers._
import org.scalatest.matchers.{BeMatcher, MatchResult}
import org.scalatest.{GivenWhenThen, WordSpec}


import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._
import scala.io.Source

class RamlTypeGeneratorTest extends WordSpec with GivenWhenThen {


  /**
    * A Scalatest be matcher that compares a string to a reference. It will print the line diffs if there are deltas.
    * @param ref Path to the reference in the Jar resources.
    */
  class CodeMatcher(ref: String) extends BeMatcher[String] {
    def apply(original: String) = {
      val refLines = Source.fromResource(ref).getLines().toList.asJava
      val deltas = DiffUtils.diff(original.lines.toList.asJava, refLines).getDeltas.asScala

      MatchResult(
        deltas.isEmpty,
        s"The generated code differed from reference $ref:\n\n${showDiff(deltas)}",
        s"The generated code was equal to reference $ref"
      )
    }

    def showDiff(deltas: Iterable[AbstractDelta[_]]): String = {
      deltas.flatMap {
        case d: DeleteDelta[_] => d.getSource().getLines().map { l => s"\t- $l" }
        case i: InsertDelta[_] => i.getSource().getLines().map { l => s"\t+ $l" }
        case c: ChangeDelta[_] => c.getSource().getLines().map { l => s"\t- $l" } ++ c.getTarget().getLines().map { l => s"\t+ $l" }
      }.mkString("\n")
    }
  }

  def equalToReference(ref: String) = new CodeMatcher(ref)

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
      typesAsStr("Foo") should be(equalToReference("references/Foo.scala"))
    }
  }
}