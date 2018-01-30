package mesosphere.marathon
package storage.migration

import mesosphere.UnitTest
import mesosphere.marathon.test.Mockito

class StorageVersionsTest extends UnitTest with Mockito {

  "The StorageVersions" should {
    "infer the correct target version" in {
      Given("A list of migration steps")
      val steps = List(
        StorageVersions(4, 5, 2) -> { _: Migration => mock[MigrationStep] },
        StorageVersions(22, 1) -> { _: Migration => mock[MigrationStep] },
        StorageVersions(10) -> { _: Migration => mock[MigrationStep] }
      )

      When("The target version is inferred")
      val target = StorageVersions(steps)

      Then("It should be the highest version independent of the order")
      target.getMajor should be(22)
      target.getMinor should be(1)
      target.getPatch should be(0)
    }
  }
}
