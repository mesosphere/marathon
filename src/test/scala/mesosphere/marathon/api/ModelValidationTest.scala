package mesosphere.marathon.api

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.api.v2.GroupUpdate
import mesosphere.marathon.state.PathId._

import java.net.{ ServerSocket, InetAddress }
import org.scalatest.{ BeforeAndAfterAll, Matchers }

class ModelValidationTest
    extends MarathonSpec
    with Matchers
    with BeforeAndAfterAll {

  test("A group update should pass validation") {
    val mv = new ModelValidation {}
    val update = GroupUpdate(id = Some("/a/b/c".toPath))

    val violations = mv.checkGroupUpdate(update, true)
    violations should have size (0)
  }

}
