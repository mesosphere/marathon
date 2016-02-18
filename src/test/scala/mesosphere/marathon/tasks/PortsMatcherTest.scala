package mesosphere.marathon.tasks

import java.util

import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.{ AppDefinition, Container, PortDefinitions }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import mesosphere.mesos.protos
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.Offer

import scala.collection.immutable.Seq
import scala.util.Random

class PortsMatcherTest extends MarathonSpec {

  import mesosphere.mesos.protos.Implicits._

  test("get random ports from single range") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81))
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(2 == matcher.ports.size)
    assert(matcher.portRanges.get.map(_.role) == Seq("*"))
  }

  test("get ports from multiple ranges") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31000))
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
    assert(matcher.portRanges.get.map(_.role) == Seq("*"))
  }

  test("get ports from multiple ranges, requirePorts") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82, 83, 100), requirePorts = true)
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(80, 83), protos.Range(100, 100))
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val matcher = new PortsMatcher(app, offer, acceptedResourceRoles = Set("*"))

    assert(matcher.matches)
    assert(matcher.ports == Seq(80, 81, 82, 83, 100))
    assert(matcher.portRanges.get.map(_.role) == Seq("*"))
  }

  // #2865 Multiple explicit ports are mixed up in task json
  test("get ports with requirePorts preserves the ports order") {
    val app = AppDefinition(portDefinitions = PortDefinitions(100, 80), requirePorts = true)
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 70, endPort = 200).build
    val matcher = new PortsMatcher(app, offer, acceptedResourceRoles = Set("*"))

    assert(matcher.matches)
    assert(matcher.ports == Seq(100, 80))
  }

  test("get ports from multiple resources, preserving role") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003))
    )
    val portsResource2 = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31000, 31000)),
      "marathon"
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .addResources(portsResource2)
      .build
    val matcher = new PortsMatcher(app, offer, acceptedResourceRoles = Set("*", "marathon"))

    assert(matcher.matches)
    assert(5 == matcher.ports.size)
    assert(matcher.portRanges.get.map(_.role).to[Set] == Set("*", "marathon"))
  }

  test("get ports from multiple ranges, ignore ranges with unwanted roles") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(30000, 30003), protos.Range(31000, 31009)),
      role = "marathon"
    )
    val offer = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(FrameworkID("marathon"))
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(portsResource)
      .build
    val matcher = new PortsMatcher(app, offer, acceptedResourceRoles = Set("*"))

    assert(!matcher.matches)
  }

  test("get no ports") {
    val app = AppDefinition(portDefinitions = Nil)
    val offer = MarathonTestHelper.makeBasicOffer().build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(
      Some(Nil) == matcher.portRanges
    )
  }

  test("get too many ports") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82))
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
  }

  test("fail if required ports are not available") {
    val app = AppDefinition(portDefinitions = PortDefinitions(80, 81, 82), requirePorts = true)
    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
    assert(None == matcher.portRanges)
  }

  test("fail if dynamic mapped port from container cannot be satisfied") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 0)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = -1).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
    assert(None == matcher.portRanges)
  }

  test("satisfy dynamic mapped port from container") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 0)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(Some(Seq(RangesResource("ports", Seq(protos.Range(31000, 31000)), "*"))) == matcher.portRanges)
  }

  test("randomly satisfy dynamic mapped port from container") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 0)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val rand = new Random(new util.Random(0))
    val matcher = new PortsMatcher(app, offer, random = rand)

    assert(matcher.matches)
    val firstPort = matcher.ports.head

    val differentMatchWithSameSeed = (1 to 10).find { _ =>
      val rand = new Random(new util.Random(0))
      val matcher = new PortsMatcher(app, offer, random = rand)
      matcher.ports.head != firstPort
    }

    assert(differentMatchWithSameSeed.isEmpty)

    val differentMatchWithDifferentSeed = (1 to 1000).find { seed =>
      val rand = new Random(new util.Random(seed.toLong))
      val matcher = new PortsMatcher(app, offer, random = rand)
      matcher.ports.head != firstPort
    }

    assert(differentMatchWithDifferentSeed.isDefined)
  }

  test("fail if fixed mapped port from container cannot be satisfied") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 8080)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
  }

  test("satisfy fixed mapped port from container") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 31200)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(Some(Seq(RangesResource("ports", Seq(protos.Range(31200, 31200)), "*"))) == matcher.portRanges)
  }

  test("do not satisfy fixed mapped port from container with resource offer of incorrect role") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 31200)
          ))
        ))
      )
    ))

    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31001, 31001)),
      role = "marathon"
    )

    val offer = MarathonTestHelper.makeBasicOffer(endPort = -1).addResources(portsResource).build
    val matcher = new PortsMatcher(app, offer)

    assert(!matcher.matches)
  }

  test("satisfy fixed and dynamic mapped port from container from one offered range") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 0),
            new Docker.PortMapping(containerPort = 1, hostPort = 31000)
          ))
        ))
      )
    ))

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
    val matcher = new PortsMatcher(app, offer)

    assert(matcher.matches)
    assert(Some(Seq(
      RangesResource("ports", Seq(protos.Range(31001, 31001), protos.Range(31000, 31000)), "*")
    )) == matcher.portRanges)
  }

  test("satisfy fixed and dynamic mapped port from container from ranges with different roles") {
    val app = AppDefinition(container = Some(
      Container(
        docker = Some(new Docker(
          portMappings = Some(Seq(
            new Docker.PortMapping(containerPort = 1, hostPort = 0),
            new Docker.PortMapping(containerPort = 1, hostPort = 31000)
          ))
        ))
      )
    ))

    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(protos.Range(31001, 31001)),
      role = "marathon"
    )

    val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).addResources(portsResource).build
    val matcher = new PortsMatcher(app, offer, acceptedResourceRoles = Set("*", "marathon"))

    assert(matcher.matches)
    assert(Some(Set(
      RangesResource("ports", Seq(protos.Range(31000, 31000)), "*"),
      RangesResource("ports", Seq(protos.Range(31001, 31001)), "marathon")
    )) == matcher.portRanges.map(_.toSet))
  }
}
