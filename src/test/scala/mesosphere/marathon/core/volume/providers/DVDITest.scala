package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

class DVDIVolumeValidationTest extends MarathonSpec with Matchers {
  case class TC(volumes: Iterable[PersistentVolume], wantsValid: Boolean)
  val PVI = PersistentVolumeInfo.apply _
  val PV = PersistentVolume.apply _
  // validation concerns are split at different levels:
  // - between state/Volume and providers/*
  //     > containerPath, in particular, in enforced in state/Volume and not at the
  //       provider-level
  // - between validateVolume, validateApp, validateGroup
  val ttValidateVolume = Array[TC](
    TC(
      // various combinations of INVALID dvdi persistent volume parameters
      Set[PersistentVolume](
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverNam" -> "bar", "dvdi/volumetype" -> "io1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/volumetype" -> "io1 ")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> " xfs")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> "")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "0")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "b")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "b")), Mode.RO),
        PV("", PVI(None, None, None, Map.empty[String, String]), Mode.RO),
        PV("", PVI(None, Some("f"), None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("qaz"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some(""), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some(""), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, None, Some("dvdi"), Map.empty[String, String]), Mode.RO),
        PV("", PVI(None, Some("f"), None, Map.empty[String, String]), Mode.RO),
        PV("", PVI(Some(1L), None, None, Map.empty[String, String]), Mode.RO)
      ), false
    ),
    TC(
      // various combinations of VALID dvdi persistent volume parameters
      Set[PersistentVolume](
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/volumetype" -> "io1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/newfstype" -> "xfs")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/iops" -> "1")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "true")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map(
          "dvdi/driverName" -> "bar", "dvdi/overwritefs" -> "false")), Mode.RO),
        PV("", PVI(Some(1L), Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar", "a" -> "b")), Mode.RO),
        PV("", PVI(Some(1L), Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PV("", PVI(None, Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO)
      ), true
    )
  )
  test("validPersistentVolume") {
    for (tc <- ttValidateVolume; v <- tc.volumes) {
      val result = validate(v)(DVDIProvider.validPersistentVolume)
      assert(result.isSuccess == tc.wantsValid,
        s"expected ${tc.wantsValid} instead of $result for volume $v")
    }
  }
}
