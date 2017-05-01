package mesosphere.marathon
package io

import mesosphere.UnitTest

class IOTest extends UnitTest {
  "IO" should {
    "Compress / unCompress works" in {
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
}
