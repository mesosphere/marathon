package mesosphere.marathon
package api.v2.validation

import mesosphere.marathon.state.{AbsolutePathId, Group, RootGroup}
import mesosphere.{UnitTest, ValidationTestLike}

class GroupValidationTest extends UnitTest with ValidationTestLike {

  "Group validation" should {
    "reject defined `enforceRole` outside of a top-level group" ignore { // TODO: fix once visitor interface is stable
      val groupValidator = Group.validNestedGroupUpdateWithBase(AbsolutePathId("/"), RootGroup.empty)
      val update = raml.GroupUpdate(
        id = Some("/prod"),
        enforceRole = Some(true),
        groups = Some(Set(raml.GroupUpdate(
          id = Some("second"), enforceRole = Some(true)
        )))
      )

      groupValidator(update) should haveViolations(
        "/groups(0)/enforceRole" -> """enforceRole can only be set for top-level groups, and /prod/second is not top-level"""
      )
    }
  }
}
