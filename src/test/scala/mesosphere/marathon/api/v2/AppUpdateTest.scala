package mesosphere.marathon.api.v2

import javax.validation.Validation
import scala.collection.JavaConverters._
import mesosphere.marathon.MarathonSpec

class AppUpdateTest extends MarathonSpec {

  test("Validation") {
    val validator = Validation.buildDefaultValidatorFactory().getValidator

    def shouldViolate(update: AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    def shouldNotViolate(update: AppUpdate, path: String, template: String) = {
      val violations = validator.validate(update).asScala
      assert(!violations.exists(v =>
        v.getPropertyPath.toString == path && v.getMessageTemplate == template))
    }

    val update = AppUpdate()

    shouldViolate(
      update.copy(ports = Some(Seq(9000, 8080, 9000))),
      "ports",
      "Elements must be unique"
    )

  }

}
