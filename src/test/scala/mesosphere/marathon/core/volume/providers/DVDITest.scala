package mesosphere.marathon.core.volume.providers

import com.wix.accord._
import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state._
import org.apache.mesos.Protos.Volume.Mode
import org.scalatest.Matchers

class DVDIVolumeValidationTest extends MarathonSpec with Matchers {
  case class TC(volumes: Iterable[PersistentVolume], wantsValid: Boolean)
  type PVI = PersistentVolumeInfo
  val tt = Array[TC](
    TC(
      Set[PersistentVolume](
        PersistentVolume("", new PVI(None, None, None, Map.empty[String, String]), Mode.RO),
        PersistentVolume("", new PVI(None, Some("f"), None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, Some("f"), Some("dvdi"), Map("dvdi/driverName" -> "")), Mode.RO),
        PersistentVolume("", new PVI(None, Some("f"), Some("dvdi"), Map("driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, Some("f"), Some(""), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, Some(""), Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, None, Some("dvdi"), Map("dvdi/driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, None, None, Map("dvdi/driverName" -> "bar")), Mode.RO),
        PersistentVolume("", new PVI(None, None, Some("dvdi"), Map.empty[String, String]), Mode.RO),
        PersistentVolume("", new PVI(None, Some("f"), None, Map.empty[String, String]), Mode.RO),
        PersistentVolume("", new PVI(Some(10L), None, None, Map.empty[String, String]), Mode.RO)
      ), false
    )
  )
  test("validPersistentVolume") {
    for (tc <- tt; v <- tc.volumes) {
      val result = validate(v)(DVDIProvider.validPersistentVolume)
      assert(result.isSuccess == tc.wantsValid,
        s"expected ${tc.wantsValid} instead of ${result.isSuccess} for volume $v")
    }
  }
}
