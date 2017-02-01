package mesosphere.marathon
package raml

import java.util

import mesosphere.UnitTest

class DefaultConversionsTest extends UnitTest with DefaultConversions {

  implicit val intToStringWrites: Writes[Int, String] = Writes { _.toString }

  "DefaultConversions" should {
    "identity can be converted automatically" in {
      val string = "test"
      string.toRaml[String] should be(string)
    }

    "An option conversion is applied automatically " in {
      Some(23).toRaml[Option[String]] should be (Some("23"))
    }

    "A sequence conversion is applied automatically" in {
      Seq(1, 2, 3).toRaml[Seq[String]] should be (Seq("1", "2", "3"))
    }

    "A java list conversion is applied automatically" in {
      util.Arrays.asList(1, 2, 3).toRaml[Seq[String]] should be (Seq("1", "2", "3"))
    }

    "A set conversion is applied automatically" in {
      Set(1, 2, 3).toRaml[Set[String]] should be (Set("1", "2", "3"))
    }

    "A map conversion is applied automatically" in {
      Map(1 -> 1, 2 -> 2).toRaml[Map[String, String]] should be (Map("1" -> "1", "2" -> "2"))
    }
  }
}
