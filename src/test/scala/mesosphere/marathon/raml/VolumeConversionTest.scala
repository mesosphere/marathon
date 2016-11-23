package mesosphere.marathon
package raml

import mesosphere.marathon.state.{ ExternalVolumeInfo, PersistentVolumeInfo }
import mesosphere.marathon.test.MarathonSpec
import org.apache.mesos.{ Protos => Mesos }

class VolumeConversionTest extends MarathonSpec {

  test("Convert a DockerVolume") {
    Given("A docker volume")
    val volume = state.DockerVolume("/container", "/host", Mesos.Volume.Mode.RW)

    When("The volume gets converted")
    val raml = volume.toRaml[AppVolume]

    Then("The converted raml volume is correct")
    raml.containerPath should be(volume.containerPath)
    raml.hostPath should be(Some(volume.hostPath))
    raml.mode should be(ReadMode.Rw)
    raml.external should be(empty)
    raml.persistent should be(empty)
  }

  test("Convert a ExternalVolume") {
    Given("A docker volume")
    val external = ExternalVolumeInfo(Some(123L), "external", "foo", Map("foo" -> "bla"))
    val volume = state.ExternalVolume("/container", external, Mesos.Volume.Mode.RW)

    When("The volume gets converted")
    val raml = volume.toRaml[AppVolume]

    Then("The converted raml volume is correct")
    raml.containerPath should be(volume.containerPath)
    raml.hostPath should be(empty)
    raml.mode should be(ReadMode.Rw)
    raml.external should be(defined)
    raml.persistent should be(empty)
    raml.external.get.name should be(external.name)
    raml.external.get.options should be(external.options)
    raml.external.get.provider should be(external.provider)
    raml.external.get.size should be(external.size)
  }

  test("Convert a PersistentVolume") {
    Given("A docker volume")
    val persistent = PersistentVolumeInfo(123L, Some(1234L), state.DiskType.Path)
    val volume = state.PersistentVolume("/container", persistent, Mesos.Volume.Mode.RW)

    When("The volume gets converted")
    val raml = volume.toRaml[AppVolume]

    Then("The converted raml volume is correct")
    raml.containerPath should be(volume.containerPath)
    raml.hostPath should be(empty)
    raml.mode should be(ReadMode.Rw)
    raml.external should be(empty)
    raml.persistent should be(defined)
    raml.persistent.get.size should be(persistent.size)
    raml.persistent.get.maxSize should be(persistent.maxSize)
    raml.persistent.get.constraints should be(empty)
  }
}
