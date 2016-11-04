package mesosphere.marathon

import mesosphere.marathon.test.{ ExitDisabledTest, MarathonActorSupport }
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, OptionValues }
import org.scalatest.mockito.MockitoSugar

trait MarathonSpec extends FunSuiteLike with BeforeAndAfter with MockitoSugar with OptionValues with MarathonActorSupport with ExitDisabledTest
