package mesosphere.marathon

import org.scalatest.{ BeforeAndAfter, FunSuiteLike, OptionValues }
import org.scalatest.mock.MockitoSugar

trait MarathonSpec extends FunSuiteLike with BeforeAndAfter with MockitoSugar with OptionValues
