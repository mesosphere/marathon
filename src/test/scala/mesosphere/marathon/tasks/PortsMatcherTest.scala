package mesosphere.marathon
package tasks

import java.util

import mesosphere.UnitTest
import mesosphere.marathon.core.pod.ContainerNetwork
import mesosphere.marathon.state.Container.{ Docker, PortMapping }
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinitions, ResourceRole }
import mesosphere.marathon.tasks.PortsMatcher.PortWithRole
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos
import mesosphere.mesos.protos._
import org.apache.mesos.Protos.Offer

import scala.collection.immutable.Seq
import scala.util.Random

class PortsMatcherTest extends UnitTest {

  import mesosphere.mesos.protos.Implicits._

  val runSpecId = PathId("/test")
  private val containerNetworking = Seq(ContainerNetwork("dcos"))

  "PortsMatcher" should {
    "get random ports from single range" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81))
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(2 == matcher.portsMatch.get.hostPorts.size)
      assert(matcher.portsMatch.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
    }

    "get ports from multiple ranges" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
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
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(5 == matcher.portsMatch.get.hostPorts.size)
      assert(matcher.portsMatch.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
    }

    "get ports from multiple ranges, requirePorts" in {
      val f = new Fixture
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 100), requirePorts = true)
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
      val matcher = PortsMatcher(app, offer, f.wildcardResourceSelector)

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten == Seq(80, 81, 82, 83, 100))
      assert(matcher.portsMatch.get.resources.map(_.getRole) == Seq(ResourceRole.Unreserved))
    }

    // #2865 Multiple explicit ports are mixed up in task json
    "get ports with requirePorts preserves the ports order" in {
      val f = new Fixture
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(100, 80), requirePorts = true)
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 70, endPort = 200).build
      val matcher = PortsMatcher(app, offer, f.wildcardResourceSelector)

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten == Seq(100, 80))
    }

    "get ports from multiple resources, preserving role" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
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
      val matcher = PortsMatcher(app, offer, resourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

      assert(matcher.portsMatch.isDefined)
      assert(5 == matcher.portsMatch.get.hostPorts.size)
      assert(matcher.portsMatch.get.resources.map(_.getRole).to[Set] == Set(ResourceRole.Unreserved, "marathon"))
    }

    "get ports from multiple ranges, ignore ranges with unwanted roles" in {
      val f = new Fixture
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82, 83, 84))
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
      val matcher = PortsMatcher(app, offer, f.wildcardResourceSelector)

      assert(matcher.portsMatch.isEmpty)
    }

    "get no ports" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = Nil)
      val offer = MarathonTestHelper.makeBasicOffer().build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(Nil == matcher.portsMatch.get.hostPorts)
    }

    "get too many ports" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82))
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isEmpty)
    }

    "fail if required ports are not available" in {
      val app = AppDefinition(id = runSpecId, portDefinitions = PortDefinitions(80, 81, 82), requirePorts = true)
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isEmpty)
    }

    "fail if dynamic mapped port from container cannot be satisfied" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(0))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = -1).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isEmpty)
    }

    "satisfy dynamic mapped port from container" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(0))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten == Seq(31000))
    }

    "randomly satisfy dynamic mapped port from container" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(0))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
      val rand = new Random(new util.Random(0))
      val matcher = PortsMatcher(app, offer, random = rand)

      assert(matcher.portsMatch.isDefined)
      val firstPort = matcher.portsMatch.get.hostPorts.head

      val differentMatchWithSameSeed: Option[Int] = (1 to 10).find { _ =>
        val rand = new Random(new util.Random(0))
        val portsMatch = PortsMatcher(app, offer, random = rand).portsMatch
        portsMatch.get.hostPorts.head != firstPort
      }

      differentMatchWithSameSeed should be(empty)

      val differentMatchWithDifferentSeed = (1 to 1000).find { seed =>
        val rand = new Random(new util.Random(seed.toLong))
        val matcher = PortsMatcher(app, offer, random = rand)
        matcher.portsMatch.get.hostPorts.head != firstPort
      }

      differentMatchWithDifferentSeed should be(defined)
    }

    "fail if fixed mapped port from container cannot be satisfied" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(8080))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isEmpty)
    }

    "satisfy fixed mapped port from container" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(31200))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 32000).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten == Seq(31200))
    }

    "do not satisfy fixed mapped port from container with resource offer of incorrect role" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(31200))
        )
      )), networks = containerNetworking)

      val portsResource = RangesResource(
        Resource.PORTS,
        Seq(protos.Range(31001, 31001)),
        role = "marathon"
      )

      val offer = MarathonTestHelper.makeBasicOffer(endPort = -1).addResources(portsResource).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isEmpty)
    }

    "satisfy fixed and dynamic mapped port from container from one offered range" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(0)),
          new PortMapping(containerPort = 1, hostPort = Some(31000))
        )
      )), networks = containerNetworking)

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31001).build
      val matcher = PortsMatcher(app, offer)

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten == Seq(31001, 31000))
    }

    "satisfy fixed and dynamic mapped port from container from ranges with different roles" in {
      val app = AppDefinition(id = runSpecId, container = Some(Docker(
        portMappings = Seq(
          new PortMapping(containerPort = 1, hostPort = Some(0)),
          new PortMapping(containerPort = 1, hostPort = Some(31000))
        )
      )), networks = containerNetworking)

      val portsResource = RangesResource(
        Resource.PORTS,
        Seq(protos.Range(31001, 31001)),
        role = "marathon"
      )

      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 31000, endPort = 31000).addResources(portsResource).build
      val matcher = PortsMatcher(app, offer, resourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")))

      assert(matcher.portsMatch.isDefined)
      assert(matcher.portsMatch.get.hostPorts.flatten.toSet == Set(31000, 31001))
      assert(matcher.portsMatch.get.hostPortsWithRole.toSet == Set( // linter:ignore:UnlikelyEquality
        Some(PortWithRole(ResourceRole.Unreserved, 31000)), Some(PortWithRole("marathon", 31001))
      ))
    }
  }
}

class Fixture {
  lazy val wildcardResourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved))
}
