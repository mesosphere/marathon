package mesosphere.marathon.test

import com.typesafe.scalalogging.StrictLogging
import mesosphere.FutureTestSupport
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, GivenWhenThen, Matchers, OptionValues }

trait MarathonSpec extends FunSuiteLike with BeforeAndAfter with MockitoSugar with OptionValues
  with FutureTestSupport with Matchers with GivenWhenThen with StrictLogging
