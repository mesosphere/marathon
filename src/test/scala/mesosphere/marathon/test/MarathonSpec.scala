package mesosphere.marathon.test

import org.scalatest.mockito.MockitoSugar
import org.scalatest.{ BeforeAndAfter, FunSuiteLike, OptionValues }

/**
  * Created by karsten on 21/09/16.
  */
trait MarathonSpec extends FunSuiteLike with BeforeAndAfter with MockitoSugar with OptionValues
