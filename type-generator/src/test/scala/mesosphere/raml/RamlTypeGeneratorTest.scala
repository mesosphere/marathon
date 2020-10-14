package mesosphere.raml

import java.io.File
import java.net.URI
import java.nio.file.Paths

import com.github.difflib.DiffUtils
import com.github.difflib.patch.{AbstractDelta, ChangeDelta, DeleteDelta, InsertDelta}
import com.typesafe.scalalogging.StrictLogging
import org.raml.v2.api.RamlModelBuilder
import org.raml.v2.api.loader.FileResourceLoader
import org.scalatest.Matchers._
import org.scalatest.matchers.{BeMatcher, MatchResult}
import org.scalatest.{GivenWhenThen, Inspectors, WordSpec}

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._
import scala.io.Source

class RamlTypeGeneratorTest extends WordSpec with GivenWhenThen with Inspectors with StrictLogging {

  /**
    * A Scalatest be matcher that compares a string to a reference. It will print the line diffs if there are deltas.
    * @param ref Path to the reference in the Jar resources.
    */
  class CodeMatcher(ref: String) extends BeMatcher[String] {
    def apply(original: String) = {
      val refLines = ref.lines.toList.asJava
      val deltas = DiffUtils.diff(original.lines.toList.asJava, refLines).getDeltas.asScala

      MatchResult(
        deltas.isEmpty,
        s"The generated code differed from reference:\n\n${showDiff(deltas)}",
        "The generated code was equal to reference."
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

  def equalToCode(ref: String) = new CodeMatcher(ref)

  "RamlTypeGenerator" should {
    "produce a primitive type" in {
      Given("A reference model")
      val file = new File("src/test/resources/api.raml")
      assert(file.exists(), "file does not exist")
      val models = Vector(new RamlModelBuilder().buildApi(file))

      When("the type is generated")
      val types = RamlTypeGenerator(models, "mesosphere.raml.test")
      val typesAsStr: Map[String, String] = types.mapValues(treehugger.forest.treeToString(_))

      Then("the type equals our reference")
      typesAsStr("Foo") should be(equalToCode(Source.fromResource("references/Foo.scala").mkString))
    }

    "produce the same type as the legacy generator" in {
      Given("The current API model")
      val file = new File("../docs/docs/rest-api/public/api/api.raml")
      assert(file.exists(), "file does not exist")
      val model = new RamlModelBuilder(new FileResourceLoader(".")).buildApi(file)
      if (model.hasErrors) {
        model.getValidationResults.asScala.foreach { error =>
          sys.error(error.toString)
        }
      }

      When("the types are generated")
      val types = RamlTypeGenerator(Vector(model), "mesosphere.raml.test")
      val typesAsStr: Map[String, String] = types.mapValues(treehugger.forest.treeToString(_))

      Then("they are the same as the legacy types")
      val legacyTypes = LegacyRamlTypeGenerator(Vector(model), "mesosphere.raml.test")
      val legacyTypesAsStr: Map[String, String] = legacyTypes.mapValues(treehugger.forest.treeToString(_))

      forEvery(typesAsStr.keys) { typeName =>
        legacyTypesAsStr should contain key (typeName)
        typesAsStr(typeName) should be(equalToCode(legacyTypesAsStr(typeName)))
      }
    }
  }
}
