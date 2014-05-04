package mesosphere.marathon

import org.scalatest.{FunSuite, BeforeAndAfter}
import org.scalatest.mock.MockitoSugar

/**
 * @author Tobi Knaup
 */

abstract class MarathonSpec extends FunSuite
  with BeforeAndAfter with MockitoSugar with MarathonTestHelper {

}
