package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper

class VolumeConstraintsTest extends UnitTest {
  val resource = MarathonTestHelper.scalarResource("disk", 1024.0,
    disk = Some(MarathonTestHelper.pathDisk("/path/to/disk-a")))

  "VolumeConstraints" should {
    "matches LIKE and UNLIKE based on path" in {
      VolumeConstraints.meetsConstraint(
        resource, MarathonTestHelper.constraint("path", "LIKE", Some(".+disk-b"))) shouldBe false
      VolumeConstraints.meetsConstraint(
        resource, MarathonTestHelper.constraint("path", "LIKE", Some(".+disk-a"))) shouldBe true

      VolumeConstraints.meetsConstraint(
        resource, MarathonTestHelper.constraint("path", "UNLIKE", Some(".+disk-b"))) shouldBe true
      VolumeConstraints.meetsConstraint(
        resource, MarathonTestHelper.constraint("path", "UNLIKE", Some(".+disk-a"))) shouldBe false
    }
  }
}
