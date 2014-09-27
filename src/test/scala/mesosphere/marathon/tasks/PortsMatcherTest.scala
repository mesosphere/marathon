package mesosphere.marathon.tasks

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.state.AppDefinition
import mesosphere.mesos.protos
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.Offer

import scala.collection.immutable.Seq

class PortsMatcherTest extends MarathonSpec {

  import mesosphere.mesos.protos.Implicits._

  test("get random ports from single range") {
    val app = AppDefinition(ports = Seq(80, 81))
    val offer = makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(2 == matcher.ports.size)
  }

  test("get ports from multiple ranges") {
    val app = AppDefinition(ports = Seq(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31009))
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(5 == matcher.ports.size)
  }

  test("get no ports") {
    val app = AppDefinition(ports = Nil)
    val offer = makeBasicOffer().build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(
      Some(RangesResource(Resource.PORTS, Nil)) ==
        matcher.portRanges
    )
  }

  test("get too many ports") {
    val app = AppDefinition(ports = Seq(80, 81, 82))
    val offer = makeBasicOffer(beginPort = 31000, endPort = 31001).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
  }

  test("get app ports if available") {
    val app = AppDefinition(ports = Seq(80, 81, 82))
    val offer = makeBasicOffer(beginPort = 80, endPort = 82).build
    val matcher = new PortsMatcher(app, offer)

    assert(
      Some(RangesResource(Resource.PORTS, Seq(protos.Range(80, 82)))) ==
        matcher.portRanges
    )
  }

  test("fail if required ports are not available") {
    val app = AppDefinition(ports = Seq(80, 81, 82), requirePorts = true)
    val offer = makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
    assert(None == matcher.portRanges)
  }
}
