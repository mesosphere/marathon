package mesosphere.marathon
package api.v2

import com.wix.accord.Descriptions.Explicit
import mesosphere.UnitTest
import com.wix.accord.{ Failure, RuleViolation }
import play.api.libs.json._

class ValidationTest extends UnitTest {
  import Validation._

  "The failure format" should {
    "write validations errors" in {
      val violation = RuleViolation(value = Some("foo"), constraint = "is a number", path = Explicit("id"))
      val failure = Failure(Set(violation))
      val json = Json.toJson(failure)
      json.toString should be("""{"message":"Object is not valid","details":[{"path":"/id","errors":["is a number"]}]}""")
    }
  }
}
