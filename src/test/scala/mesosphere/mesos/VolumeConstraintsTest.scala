package mesosphere.mesos

import mesosphere.marathon.test.{ MarathonSpec, MarathonTestHelper }
import org.scalatest.Matchers

class VolumeConstraintsTest extends MarathonSpec with Matchers {
  val resource = MarathonTestHelper.scalarResource("disk", 1024.0,
    disk = Some(MarathonTestHelper.pathDisk("/path/to/disk-a")))

  test("matches LIKE and UNLIKE based on path") {
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
