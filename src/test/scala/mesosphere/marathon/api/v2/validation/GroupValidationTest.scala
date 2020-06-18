package mesosphere.marathon
package api.v2.validation

import mesosphere.marathon.state.{AbsolutePathId, Group, RootGroup}
import mesosphere.{UnitTest, ValidationTestLike}

class GroupValidationTest extends UnitTest with ValidationTestLike {

  "Group validation" should {
    "reject defined `enforceRole` outside of a top-level group" in {
      val groupValidator = Group.validNestedGroupUpdateWithBase(AbsolutePathId("/"), RootGroup.empty(), false)
      val update = raml.GroupUpdate(
        id = Some("/prod"),
        enforceRole = Some(true),
        groups = Some(
          Set(
            raml.GroupUpdate(
              id = Some("second"),
              enforceRole = Some(true)
            )
          )
        )
      )

      groupValidator(update) should haveViolations(
        "/groups(0)/enforceRole" -> """enforceRole can only be set for top-level groups, and /prod/second is not top-level"""
      )
    }

    "disallows changes to enforceRole if other services are modified with the request" in {
      val originalGroup = RootGroup.empty().putGroup(Group(AbsolutePathId("/dev"), enforceRole = false))
      val groupValidator = Group.validNestedGroupUpdateWithBase(AbsolutePathId("/"), originalGroup, servicesGloballyModified = true)

      val update = raml.GroupUpdate(id = Some("/dev"), enforceRole = Some(true))

      groupValidator(update) should haveViolations(
        "/enforceRole" -> Group.disallowEnforceRoleChangeIfServicesChanged.EnforceRoleCantBeChangedMessage
      )
    }

    "allows changes to enforceRole if other services are not modified with the request" in {
      val originalGroup = RootGroup.empty().putGroup(Group(AbsolutePathId("/dev"), enforceRole = false))
      val groupValidator = Group.validNestedGroupUpdateWithBase(AbsolutePathId("/"), originalGroup, servicesGloballyModified = false)

      val update = raml.GroupUpdate(id = Some("/dev"), enforceRole = Some(true))

      groupValidator(update) shouldBe aSuccess
    }
  }
}
