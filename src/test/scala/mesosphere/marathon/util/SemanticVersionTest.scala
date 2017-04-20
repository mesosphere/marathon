package mesosphere.marathon
package util

import mesosphere.UnitTest

class SemanticVersionTest extends UnitTest {
  "greaterThan" should {
    "work for major" in {
      SemanticVersion(2, 0, 0) > SemanticVersion(1, 0, 0) should be(true)
      SemanticVersion(1, 0, 0) > SemanticVersion(1, 0, 0) should be(false)
      SemanticVersion(0, 0, 0) > SemanticVersion(1, 0, 0) should be(false)
    }
    "work for minor" in {
      SemanticVersion(1, 2, 0) > SemanticVersion(1, 1, 0) should be(true)
      SemanticVersion(1, 1, 0) > SemanticVersion(1, 1, 0) should be(false)
      SemanticVersion(1, 0, 0) > SemanticVersion(1, 1, 0) should be(false)
    }
    "work for patch" in {
      SemanticVersion(1, 0, 2) > SemanticVersion(1, 0, 1) should be(true)
      SemanticVersion(1, 0, 1) > SemanticVersion(1, 0, 1) should be(false)
      SemanticVersion(1, 0, 0) > SemanticVersion(1, 0, 1) should be(false)
    }
  }
  "greaterThanOrEqualTo" should {
    "work for major" in {
      SemanticVersion(2, 0, 0) >= SemanticVersion(1, 0, 0) should be(true)
      SemanticVersion(1, 0, 0) >= SemanticVersion(1, 0, 0) should be(true)
      SemanticVersion(0, 0, 0) >= SemanticVersion(1, 0, 0) should be(false)
    }
    "work for minor" in {
      SemanticVersion(1, 2, 0) >= SemanticVersion(1, 1, 0) should be(true)
      SemanticVersion(1, 1, 0) >= SemanticVersion(1, 1, 0) should be(true)
      SemanticVersion(1, 0, 0) >= SemanticVersion(1, 1, 0) should be(false)
    }
    "work for patch" in {
      SemanticVersion(1, 0, 2) >= SemanticVersion(1, 0, 1) should be(true)
      SemanticVersion(1, 0, 1) >= SemanticVersion(1, 0, 1) should be(true)
      SemanticVersion(1, 0, 0) >= SemanticVersion(1, 0, 1) should be(false)
    }
  }
  "equalTo" should {
    "work for major" in {
      SemanticVersion(2, 0, 0) == SemanticVersion(1, 0, 0) should be(false)
      SemanticVersion(1, 0, 0) == SemanticVersion(1, 0, 0) should be(true) // linter:ignore ReflexiveComparison
      SemanticVersion(0, 0, 0) == SemanticVersion(1, 0, 0) should be(false)
    }
    "work for minor" in {
      SemanticVersion(1, 2, 0) == SemanticVersion(1, 1, 0) should be(false)
      SemanticVersion(1, 1, 0) == SemanticVersion(1, 1, 0) should be(true) // linter:ignore ReflexiveComparison
      SemanticVersion(1, 0, 0) == SemanticVersion(1, 1, 0) should be(false)
    }
    "work for patch" in {
      SemanticVersion(1, 0, 2) == SemanticVersion(1, 0, 1) should be(false)
      SemanticVersion(1, 0, 1) == SemanticVersion(1, 0, 1) should be(true) // linter:ignore ReflexiveComparison
      SemanticVersion(1, 0, 0) == SemanticVersion(1, 0, 1) should be(false)
    }
  }
  "lessThanOrEqualTo" should {
    "work for major" in {
      SemanticVersion(2, 0, 0) <= SemanticVersion(1, 0, 0) should be(false)
      SemanticVersion(1, 0, 0) <= SemanticVersion(1, 0, 0) should be(true)
      SemanticVersion(0, 0, 0) <= SemanticVersion(1, 0, 0) should be(true)
    }
    "work for minor" in {
      SemanticVersion(1, 2, 0) <= SemanticVersion(1, 1, 0) should be(false)
      SemanticVersion(1, 1, 0) <= SemanticVersion(1, 1, 0) should be(true)
      SemanticVersion(1, 0, 0) <= SemanticVersion(1, 1, 0) should be(true)
    }
    "work for patch" in {
      SemanticVersion(1, 0, 2) <= SemanticVersion(1, 0, 1) should be(false)
      SemanticVersion(1, 0, 1) <= SemanticVersion(1, 0, 1) should be(true)
      SemanticVersion(1, 0, 0) <= SemanticVersion(1, 0, 1) should be(true)
    }
  }
  "lessThan" should {
    "work for major" in {
      SemanticVersion(2, 0, 0) < SemanticVersion(1, 0, 0) should be(false)
      SemanticVersion(1, 0, 0) < SemanticVersion(1, 0, 0) should be(false)
      SemanticVersion(0, 0, 0) < SemanticVersion(1, 0, 0) should be(true)
    }
    "work for minor" in {
      SemanticVersion(1, 2, 0) < SemanticVersion(1, 1, 0) should be(false)
      SemanticVersion(1, 1, 0) < SemanticVersion(1, 1, 0) should be(false)
      SemanticVersion(1, 0, 0) < SemanticVersion(1, 1, 0) should be(true)
    }
    "work for patch" in {
      SemanticVersion(1, 0, 2) < SemanticVersion(1, 0, 1) should be(false)
      SemanticVersion(1, 0, 1) < SemanticVersion(1, 0, 1) should be(false)
      SemanticVersion(1, 0, 0) < SemanticVersion(1, 0, 1) should be(true)
    }
  }
  "parsing" should {
    "work for basic" in {
      val parsed = SemanticVersion("1.2.3")
      parsed.isDefined should be (true)
      SemanticVersion(1, 2, 3) == parsed.get should be (true)
    }
    "world for postfixed" in {
      val parsed = SemanticVersion("1.2.3-SNAPSHOT")
      parsed.isDefined should be (true)
      SemanticVersion(1, 2, 3) == parsed.get should be (true)
    }
    "work for multiple digits" in {
      var parsed = SemanticVersion("11.22.33")
      parsed.isDefined should be (true)
      SemanticVersion(11, 22, 33) == parsed.get should be (true)

      parsed = SemanticVersion("11.22.33-SNAPSHOT")
      parsed.isDefined should be (true)
      SemanticVersion(11, 22, 33) == parsed.get should be (true)
    }
  }
}
