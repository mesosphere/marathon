package mesosphere.marathon
package raml

import java.util

import mesosphere.FunTest

class DefaultConversionsTest extends FunTest with DefaultConversions {

  implicit val intToStringWrites: Writes[Int, String] = Writes { _.toString }

  test("identity can be converted automatically") {
    val string = "test"
    string.toRaml[String] should be(string)
  }

  test("An option conversion is applied automatically ") {
    Some(23).toRaml[Option[String]] should be (Some("23"))
  }

  test("A sequence conversion is applied automatically") {
    Seq(1, 2, 3).toRaml[Seq[String]] should be (Seq("1", "2", "3"))
  }

  test("A java list conversion is applied automatically") {
    util.Arrays.asList(1, 2, 3).toRaml[Seq[String]] should be (Seq("1", "2", "3"))
  }

  test("A set conversion is applied automatically") {
    Set(1, 2, 3).toRaml[Seq[String]] should be (Seq("1", "2", "3"))
  }

  test("A map conversion is applied automatically") {
    Map(1 -> 1, 2 -> 2).toRaml[Map[String, String]] should be (Map("1" -> "1", "2" -> "2"))
  }
}
