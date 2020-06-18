package mesosphere.marathon
package api.v2.normalization

import mesosphere.UnitTest
import mesosphere.marathon.api.v2.GroupNormalization
import mesosphere.marathon.state.{AbsolutePathId, RootGroup}
import org.rogach.scallop.ScallopConf

class GroupNormalizationTest extends UnitTest {

  val config: MarathonConf = new ScallopConf(Seq("--master", "foo")) with MarathonConf {
    verify()
  }

  "Group normalization" should {
    "not override an invalid `enforceRole` field" in {
      val invalidUpdate = raml.GroupUpdate(
        id = Some("/prod"),
        enforceRole = Some(true),
        groups = Some(
          Set(
            raml.GroupUpdate(
              id = Some("second"),
              enforceRole = Some(true) // This is invalid
            )
          )
        )
      )

      val normalized = GroupNormalization(config, RootGroup.empty()).updateNormalization(AbsolutePathId("/prod")).normalized(invalidUpdate)
      normalized.groups.value.head.enforceRole should be(Some(true))
    }
  }
}
