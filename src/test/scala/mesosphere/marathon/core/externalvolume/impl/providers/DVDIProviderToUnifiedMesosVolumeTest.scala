package mesosphere.marathon.core.externalvolume.impl.providers

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.{ ExternalVolume, ExternalVolumeInfo }
import org.apache.mesos.Protos.{ Parameter, Parameters, Volume }
import org.scalatest.Matchers

import scala.collection.JavaConverters._

class DVDIProviderVolumeToUnifiedMesosVolumeTest extends MarathonSpec with Matchers {
  import DVDIProviderVolumeToUnifiedMesosVolumeTest._

  case class TestParameters(
    externalVolume: ExternalVolume,
    wantsVol: Volume)

  val testParameters = Seq[TestParameters](
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map("dvdi/driver" -> "bar")), Volume.Mode.RO),
      wantsVol = volumeWith(
        containerPath("/path"),
        mode(Volume.Mode.RO),
        volumeRef(driver = "bar", name = "foo")
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(Some(1L), "foo", "dvdi", Map("dvdi/driver" -> "bar")), Volume.Mode.RO),
      wantsVol = volumeWith(
        containerPath("/path"),
        mode(Volume.Mode.RO),
        volumeRef("bar", "foo"),
        options(Map("size" -> "1"))
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(Some(1L), "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "2"
      )), Volume.Mode.RO),
      wantsVol = volumeWith(
        containerPath("/path"),
        mode(Volume.Mode.RO),
        volumeRef("bar", "foo"),
        options(Map("size" -> "1"))
      )
    ),
    TestParameters(
      ExternalVolume("/path", ExternalVolumeInfo(None, "foo", "dvdi", Map(
        "dvdi/driver" -> "bar",
        "dvdi/size" -> "abc"
      )), Volume.Mode.RO),
      wantsVol = volumeWith(
        containerPath("/path"),
        mode(Volume.Mode.RO),
        volumeRef("bar", "foo"),
        options(Map("size" -> "abc"))
      )
    ) // TestParameters
  )
  for ((testParams, idx) <- testParameters.zipWithIndex) {
    test(s"toUnifiedMesosVolume $idx") {
      assertResult(testParams.wantsVol, "generated volume doesn't match expectations") {
        DVDIProvider.Builders.toUnifiedContainerVolume(testParams.externalVolume)
      }
    }
  }
}

// DVDIProviderVolumeToUnifiedMesosVolumeTest contains helper types and methods for testing DVDI volumes
// with Mesos containers.
object DVDIProviderVolumeToUnifiedMesosVolumeTest {
  trait Opt extends Function1[Volume.Builder, Opt]

  def containerPath(p: String): Opt = new Opt {
    override def apply(v: Volume.Builder): Opt = {
      val old = v.getContainerPath
      v.setContainerPath(p)
      containerPath(old)
    }
  }

  def mode(m: Volume.Mode): Opt = new Opt {
    override def apply(v: Volume.Builder): Opt = {
      val old = v.getMode
      v.setMode(m)
      mode(old)
    }
  }

  // required fields for a DockerVolume
  def volumeRef(driver: String, name: String): Opt = new Opt {
    override def apply(v: Volume.Builder): Opt = {
      val oldDriver: Option[String] = {
        if (v.hasSource && v.getSource.hasDockerVolume && v.getSource.getDockerVolume.hasDriver) {
          Some(v.getSource.getDockerVolume.getDriver)
        } else None
      }
      val oldName: Option[String] = {
        if (v.hasSource && v.getSource.hasDockerVolume && v.getSource.getDockerVolume.hasName) {
          Some(v.getSource.getDockerVolume.getName)
        } else None
      }
      val sb: Volume.Source.Builder =
        if (v.hasSource) v.getSource.toBuilder
        else {
          Volume.Source.newBuilder
            .setType(Volume.Source.Type.DOCKER_VOLUME)
        }
      val dv: Volume.Source.DockerVolume.Builder =
        if (sb.hasDockerVolume) sb.getDockerVolume.toBuilder
        else Volume.Source.DockerVolume.newBuilder
      if (driver == "") dv.clearDriver() else dv.setDriver(driver)
      if (name == "") dv.clearName() else dv.setName(name)
      sb.setDockerVolume(dv)
      v.setSource(sb)
      volumeRef(oldDriver.getOrElse(""), oldName.getOrElse(""))
    }
  }

  def options(opts: Map[String, String]): Opt = new Opt {
    override def apply(v: Volume.Builder): Opt = {
      val old: Map[String, String] = {
        if (v.hasSource && v.getSource.hasDockerVolume && v.getSource.getDockerVolume.hasDriverOptions) {
          Map[String, String](v.getSource.getDockerVolume.getDriverOptions.getParameterList().asScala.map { p =>
            p.getKey() -> p.getValue()
          }.toList: _*)
        } else Map.empty[String, String]
      }
      val sb: Volume.Source.Builder =
        if (v.hasSource) v.getSource.toBuilder
        else {
          Volume.Source.newBuilder
            .setType(Volume.Source.Type.DOCKER_VOLUME)
        }
      val dv: Volume.Source.DockerVolume.Builder =
        if (sb.hasDockerVolume) sb.getDockerVolume.toBuilder
        else Volume.Source.DockerVolume.newBuilder
      if (opts.isEmpty) dv.clearDriverOptions()
      else dv.setDriverOptions(Parameters.newBuilder.addAllParameter(
        opts.map{ case (k, v) => Parameter.newBuilder.setKey(k).setValue(v).build }.asJava))
      sb.setDockerVolume(dv)
      v.setSource(sb)
      options(old)
    }
  }

  def volumeWith(opts: Opt*): Volume = {
    val v = Volume.newBuilder
    for (o <- opts) {
      o(v)
    }
    v.build
  }
}
