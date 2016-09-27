package mesosphere.marathon.test

import mesosphere.FutureTestSupport
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, OptionValues }

trait MarathonSpec extends FunSuiteLike with BeforeAndAfter with MockitoSugar with OptionValues
  with FutureTestSupport