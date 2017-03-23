package mesosphere.mesos

import com.wix.accord._
import mesosphere.UnitTest
import mesosphere.marathon.state.{ PersistentVolume, PersistentVolumeInfo, Volume }
import org.apache.mesos

class PersistentVolumeValidationTest extends UnitTest {
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
      val validation = Volume.validVolume(Set())(volume)
      validation.isSuccess should be (false)

      Then("A validation exists with a readable error message")
      validation match {
        case Failure(violations) =>
          violations should contain (RuleViolation("/path", "value must not contain \"/\"", Some("containerPath")))
        case Success =>
          fail("validation should fail!")
      }
    }
  }
}
