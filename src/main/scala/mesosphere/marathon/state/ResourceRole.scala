package mesosphere.marathon.state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._

object ResourceRole {
  val Unreserved = "*"

  // NOTE: The validators below use conjunction over `isTrue` in order to provide more user-friendly error messages.
  //       For example, `char is notEqualTo('\u0020')` would print something like "char is equal to  " as opposed to
  //       "A role name must not include a space (\x20) character".

  def validAcceptedResourceRoles(isResident: Boolean): Validator[Set[String]] =
    validator[Set[String]] { acceptedResourceRoles =>
      acceptedResourceRoles is notEmpty
      acceptedResourceRoles.each is valid(ResourceRole.validResourceRole)
    } and isTrue("""A resident app must have `acceptedResourceRoles = ["*"]`.""") { acceptedResourceRoles =>
      !isResident || acceptedResourceRoles == Set(ResourceRole.Unreserved)
    }

  val validResourceRole: Validator[String] = {
    val message = "A role name must not be %s."
    isTrue[String](message.format("\"\"")) { role => role != ""; } and
      isTrue[String](message.format("\".\"")) { role => role != "."; } and
      isTrue[String](message.format("\"..\"")) { role => role != ".."; } and
      isTrue[String]("A role name must not start with a '-'.") { role => !role.startsWith("-") } and
      validator[String] { role =>
        role.each is valid(validResourceRoleChar)
      }
  }

  val validResourceRoleChar: Validator[Char] = {
    val message = "A role name must not include a %s character."
    isTrue[Char](message.format("horizontal tab (\\x09)")) { char => char != '\u0009'; } and
      isTrue[Char](message.format("line feed (\\x0a)")) { char =>
        char != '\u000a';
      } and
      isTrue[Char](message.format("vertical tab (\\x0b)")) { char => char != '\u000b'; } and
      isTrue[Char](message.format("form feed (\\x0c)")) { char => char != '\u000c'; } and
      isTrue[Char](message.format("carriage return (\\x0d)")) { char => char != '\u000d'; } and
      isTrue[Char](message.format("space (\\x20)")) { char => char != '\u0020'; } and
      isTrue[Char](message.format("slash (\\x2f)")) { char => char != '\u002f'; } and
      isTrue[Char](message.format("backspace (\\x7f)")) { char => char != '\u007f'; }
  }
}
