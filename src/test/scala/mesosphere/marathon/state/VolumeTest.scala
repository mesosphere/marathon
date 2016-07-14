package mesosphere.marathon.state

import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.marathon.api.v2.ValidationHelper
import com.wix.accord._
import org.scalatest.Matchers

class VolumeTest extends MarathonSpec with Matchers {
  import MarathonTestHelper.constraint

  test("validating PersistentVolumeInfo constraints accepts an empty constraint list") {
    val pvi = PersistentVolumeInfo(
      1024,
      constraints = Set.empty)

    validate(pvi).isSuccess shouldBe true
  }

  test("validating PersistentVolumeInfo constraints rejects unsupported fields") {
    val pvi = PersistentVolumeInfo(
      1024,
      constraints = Set(constraint("invalid", "LIKE", Some("regex"))))

    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe (Set("Unsupported field"))
  }

  test("validating PersistentVolumeInfo constraints rejects bad regex") {
    val pvi = PersistentVolumeInfo(
      1024,
      constraints = Set(constraint("path", "LIKE", Some("(bad regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe false
    ValidationHelper.getAllRuleConstrains(result).map(_.message) shouldBe (Set("Invalid regular expression"))
  }

  test("validating PersistentVolumeInfo accepts a valid constraint") {
    val pvi = PersistentVolumeInfo(
      1024,
      constraints = Set(constraint("path", "LIKE", Some("valid regex"))))
    val result = validate(pvi)
    result.isSuccess shouldBe true
  }
}
