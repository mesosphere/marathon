package mesosphere.marathon

import org.scalatest.{ FunSuiteLike, FunSuite, BeforeAndAfter }
import org.scalatest.mock.MockitoSugar

trait MarathonSpec extends FunSuiteLike
    with BeforeAndAfter with MockitoSugar with MarathonTestHelper {

}
