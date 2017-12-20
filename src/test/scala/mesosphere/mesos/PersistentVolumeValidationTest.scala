package mesosphere.mesos

import mesosphere.{ UnitTest, ValidationTestLike }
import mesosphere.marathon.state._

class PersistentVolumeValidationTest extends UnitTest with ValidationTestLike {
  "PersistentVolumeValidation" should {
    "create a PersistentVolume with no validation violations" in {
      Given("a PersistentVolume with no validation violations")
      val volume = PersistentVolume(None, PersistentVolumeInfo(1))

      When("The volume is created and validation succeeded")
      volume should not be null
      val validation = Volume.validVolume(Set())(volume)

      Then("A validation exists with no error message")
      validation.isSuccess should be (true)
    }

    "create a PersistentVolume with validation violation in containerPath" in {
      Given("a PersistentVolume with validation violation in containerPath")
      val persistentVolume = PersistentVolume(None, PersistentVolumeInfo(1))
      val mount = VolumeMount(None, "/path")
      val volume = VolumeWithMount(persistentVolume, mount)

      When("The volume is created and validation failed")
      volume should not be null

      Then("A validation exists with a readable error message")
      VolumeWithMount.validVolumeWithMount(Set())(volume) should haveViolations(
        "/mount/mountPath" -> "value must not contain \"/\"")
    }
  }
}
