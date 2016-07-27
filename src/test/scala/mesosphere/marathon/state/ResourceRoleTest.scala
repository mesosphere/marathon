package mesosphere.marathon.state

import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.MarathonSpec
import org.scalatest.Matchers

class ResourceRoleTest extends MarathonSpec with Matchers {

  test("If `acceptedResourceRoles` is not provided, an app should be valid regardless of its residency.") {
    optional(ResourceRole.validAcceptedResourceRoles(isResident = false))(None).isSuccess shouldBe true
    optional(ResourceRole.validAcceptedResourceRoles(isResident = true))(None).isSuccess shouldBe true
  }

  test("If `acceptedResourceRoles` is provided, a non-resident app is valid only if it contains valid role names.") {
    val validate = ResourceRole.validAcceptedResourceRoles(isResident = false)

    // Valid cases.

    validate(Set("*")).isSuccess shouldBe true
    validate(Set("role")).isSuccess shouldBe true
    validate(Set("role1", "role2")).isSuccess shouldBe true
    validate(Set("...", "<<>>")).isSuccess shouldBe true
    validate(Set("role--")).isSuccess shouldBe true

    // Invalid cases.

    validate(Set.empty).isSuccess shouldBe false
    validate(Set.empty).toString should include("must not be empty")

    validate(Set("")).isSuccess shouldBe false
    validate(Set("")).toString should include("must not be \"\"")

    validate(Set(".")).isSuccess shouldBe false
    validate(Set(".")).toString should include("must not be \".\"")

    validate(Set("..")).isSuccess shouldBe false
    validate(Set("..")).toString should include("must not be \"..\"")

    validate(Set("-role")).isSuccess shouldBe false
    validate(Set("-role")).toString should include("must not start with a '-'")

    validate(Set(" ")).isSuccess shouldBe false
    validate(Set(" ")).toString should include("must not include a space")

    validate(Set("x\ny")).isSuccess shouldBe false
    validate(Set("x\ny")).toString should include("must not include a line feed")

    validate(Set("x\n", "y\n\r")).isSuccess shouldBe false
    validate(Set("x\n", "y\n\r")).toString should include("must not include a line feed")
    validate(Set("x\n", "y\n\r")).toString should include("must not include a carriage return")
  }

  test("""If `acceptedResourceRoles` is provided, a resident app is valid only if it is set to ["*"].""") {
    val validate = ResourceRole.validAcceptedResourceRoles(isResident = true)

    // Valid case.

    validate(Set("*")).isSuccess shouldBe true

    // Invalid cases.

    validate(Set("role")).isSuccess shouldBe false
    validate(Set("role")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("role1", "role2")).isSuccess shouldBe false
    validate(Set("role1", "role2")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    // Invalid role names are still invalid. The final error has the residency violation augmented.

    validate(Set.empty).isSuccess shouldBe false
    validate(Set.empty).toString should include("must not be empty")
    validate(Set.empty).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("")).isSuccess shouldBe false
    validate(Set("")).toString should include("must not be \"\"")
    validate(Set("")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set(".")).isSuccess shouldBe false
    validate(Set(".")).toString should include("must not be \".\"")
    validate(Set(".")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("..")).isSuccess shouldBe false
    validate(Set("..")).toString should include("must not be \"..\"")
    validate(Set("..")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("-role")).isSuccess shouldBe false
    validate(Set("-role")).toString should include("must not start with a '-'")
    validate(Set("-role")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set(" ")).isSuccess shouldBe false
    validate(Set(" ")).toString should include("must not include a space")
    validate(Set(" ")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("x\ny")).isSuccess shouldBe false
    validate(Set("x\ny")).toString should include("must not include a line feed")
    validate(Set("x\ny")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")

    validate(Set("x\n", "y\n\r")).isSuccess shouldBe false
    validate(Set("x\n", "y\n\r")).toString should include("must not include a line feed")
    validate(Set("x\n", "y\n\r")).toString should include("must not include a carriage return")
    validate(Set("x\n", "y\n\r")).toString should include("""must have `acceptedResourceRoles = ["*"]`""")
  }
}
