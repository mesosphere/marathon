package mesosphere.marathon

import org.scalatest.junit.AssertionsForJUnit
import org.junit.Test

class NeededResourcesPlanTest extends AssertionsForJUnit with MarathonTestHelper {

  @Test
  def saturateOffer() {
    val app1 = makeBasicApp().copy(cpus = 2.0, mem = 2048, ports = Seq(31800))
    val app2 = makeBasicApp().copy(cpus = 2.0, mem = 2048, ports = Seq(31900))
    val app3 = makeBasicApp().copy(cpus = 1.5, mem = 512, ports = Seq(31500))
    val app4 = makeBasicApp().copy(cpus = 0.5, mem = 348, ports = Seq(31800))
    val app5 = makeBasicApp().copy(cpus = 0.5, mem = 348, ports = Seq(31400))
    val offer = makeBasicOffer(cpus = 4.0, mem = 3072)

    val planner = new NeededResourcesPlan(Seq(app1, app4, app3, app5, app2))
    val result = planner.createPlan(offer.build())
    assert(result.nonEmpty)
    assert(result.size == 3)
    assert(Seq(app1, app3, app5) == result)
  }
}