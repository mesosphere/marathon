package mesosphere.marathon

import org.scalatest.{ FunSuite, BeforeAndAfter }
import org.scalatest.mock.MockitoSugar

abstract class MarathonSpec extends FunSuite
    with BeforeAndAfter with MockitoSugar with MarathonTestHelper {

}
