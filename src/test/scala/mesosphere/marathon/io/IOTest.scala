package mesosphere.marathon.io

import mesosphere.marathon.MarathonSpec
import org.scalatest.{ Matchers, GivenWhenThen }

class IOTest extends MarathonSpec with GivenWhenThen with Matchers {

  test("Compress / unCompress works") {
    Given("A byte array")
    val hello = 1.to(100).map(num => s"Hello number $num!").mkString(", ").getBytes("UTF-8")

    When("compress and decompress")
    val compressed = IO.gzipCompress(hello)
    val uncompressed = IO.gzipUncompress(compressed)

    Then("compressed is smaller and round trip works")
    compressed.length should be < uncompressed.length
    uncompressed should be(hello)
  }
}
