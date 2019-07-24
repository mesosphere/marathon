package mesosphere.marathon
package api.v2.validation

import mesosphere.marathon.state.{Group, PathId, RootGroup}
import mesosphere.{UnitTest, ValidationTestLike}

class GroupValidationTest extends UnitTest with ValidationTestLike {

  "Group validation" should {
    "reject defined `enforceRole` outside of a top-level group" in {
      val groupValidator = Group.validNestedGroupUpdateWithBase(PathId("/"), RootGroup.empty)
      val update = raml.GroupUpdate(
        id = Some("/prod"),
        enforceRole = Some(true),
        groups = Some(Set(raml.GroupUpdate(
          id = Some("second"), enforceRole = Some(true)
        )))
      )

      val validation = groupValidator(update)
      validation.isFailure shouldBe true
      validation.toString should include("\"/prod/second\" Path needs to be top-level")
    }
  }
}
