package mesosphere.mesos

import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.state.{ PersistentVolume, PersistentVolumeInfo, Volume }
import org.apache.mesos

class PersistentVolumeValidationTest extends UnitTest with ValidationTestLike {
  "PersistentVolumeValidation" should {
    "create a PersistentVolume with no validation violations" in {
      Given("a PersistentVolume with no validation violations")
      val path = "path"
      val volume = PersistentVolume(path, PersistentVolumeInfo(1), mesos.Protos.Volume.Mode.RW)

      When("The volume is created and validation succeeded")
      volume should not be null
      val validation = Volume.validVolume(Set())(volume)

      Then("A validation exists with no error message")
      validation.isSuccess should be (true)
    }

    "create a PersistentVolume with validation violation in containerPath" in {
      Given("a PersistentVolume with validation violation in containerPath")
      val path = "/path"
      val volume = PersistentVolume(path, PersistentVolumeInfo(1), mesos.Protos.Volume.Mode.RW)

      When("The volume is created and validation failed")
      volume should not be null
      volume.containerPath should be (path)

      Then("A validation exists with a readable error message")
      Volume.validVolume(Set())(volume) should haveViolations(
        "/containerPath" -> "value must not contain \"/\"")
    }
  }
}
