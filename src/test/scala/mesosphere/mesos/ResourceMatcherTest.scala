package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon._
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.core.launcher.impl.TaskLabels
import mesosphere.marathon.core.pod.{ BridgeNetwork, ContainerNetwork }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.AgentTestDefaults
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.VersionInfo._
import mesosphere.marathon.state._
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.tasks.PortsMatcher
import mesosphere.marathon.test.{ MarathonTestHelper, SettableClock }
import mesosphere.mesos.NoOfferMatchReason.DeclinedScarceResources
import mesosphere.mesos.ResourceMatcher.ResourceSelector
import mesosphere.mesos.protos.Implicits._
import mesosphere.mesos.protos.{ Resource, TextAttribute }
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.Attribute
import org.apache.mesos.{ Protos => Mesos }
import org.scalatest.Inside

import org.scalatest.prop.TableDrivenPropertyChecks

class ResourceMatcherTest extends UnitTest with Inside with TableDrivenPropertyChecks {

  implicit val clock = new SettableClock()
  implicit val config = AllConf.withTestConfig()

  "ResourceMatcher" should {
    "match with app.disk == 0, even if no disk resource is contained in the offer" in {
      val offerBuilder = MarathonTestHelper.makeBasicOffer()
      val diskResourceIndex = offerBuilder.getResourcesList.toIndexedSeq.indexWhere(_.getName == "disk")
      offerBuilder.removeResources(diskResourceIndex)
      val offer = offerBuilder.build()

      offer.getResourcesList.find(_.getName == "disk") should be('empty)

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.DISK) should be(empty)

      res.hostPorts should have size 2
    }

    "match resources success" in {
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.DISK) should be(empty)

      res.hostPorts should have size 2
    }

    "match resources success with BRIDGE and portMappings" in {
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = Nil,
        networks = Seq(BridgeNetwork()), container = Some(Container.Docker(
          image = "foo/bar",

          portMappings = Seq(
            Container.PortMapping(31001, Some(0), 0, "tcp", Some("qax")),
            Container.PortMapping(31002, Some(0), 0, "tcp", Some("qab"))
          )
        ))
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.DISK) should be(empty)

      res.hostPorts should have size 2
    }

    "match resources success with USER and portMappings" in {
      val offer = MarathonTestHelper.makeBasicOffer().build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = Nil,
        networks = Seq(ContainerNetwork("whatever")), container = Some(Container.Docker(
          image = "foo/bar",

          portMappings = Seq(
            Container.PortMapping(0, Some(0), 0, "tcp", Some("yas")),
            Container.PortMapping(31001, None, 0, "tcp", Some("qax")),
            Container.PortMapping(31002, Some(0), 0, "tcp", Some("qab"))
          )
        ))
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatch(Resource.CPUS).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.MEM).get.roles should be(Seq(ResourceRole.Unreserved))
      res.scalarMatch(Resource.DISK) should be(empty)

      res.hostPorts should have size 3
      res.hostPorts.flatten should have size 2 // linter:ignore:AvoidOptionMethod
    }

    "match resources success with preserved reservations" in {
      val labels = TaskLabels.labelsForTask(FrameworkId("foo"), Task.Id("bar")).labels
      val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels)
      val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels)
      val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels)
      val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels)
      val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal", labels)

      val offer =
        MarathonTestHelper.makeBasicOffer(role = "marathon")
          .clearResources()
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
          .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
          .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
          .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
          .build()

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 2.0, mem = 128.0, disk = 2.0),
        portDefinitions = PortDefinitions(0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.reservedWithLabels(Set(ResourceRole.Unreserved, "marathon"), labels), config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatches should have size 3
      res.scalarMatch(Resource.CPUS).get.consumed.toSet should be(
        Set(
          GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation)),
          GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation2))
        )
      )

      res.scalarMatch(Resource.MEM).get.consumed.toSet should be(
        Set(
          GeneralScalarMatch.Consumption(128.0, ResourceRole.Unreserved, reservation = Some(memReservation))
        )
      )
      res.scalarMatch(Resource.DISK).get.consumed.toSet should be(
        Set(
          DiskResourceMatch.Consumption(2.0, ResourceRole.Unreserved, Some(diskReservation), DiskSource.root, None)
        )
      )

      res.portsMatch.hostPortsWithRole.toSet should be(
        Set(Some(PortsMatcher.PortWithRole(ResourceRole.Unreserved, 80, reservation = Some(portsReservation))))
      )

      // reserved resources with labels should not be matched by selector if don't match for reservation with labels
      ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")), config, Seq.empty) shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "dynamically reserved resources are matched if they have no labels" in {
      val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal")
      val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal")
      val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
      val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
      val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal")

      val offer =
        MarathonTestHelper.makeBasicOffer(role = "marathon")
          .clearResources()
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
          .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
          .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
          .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
          .build()

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 2.0, mem = 128.0, disk = 2.0),
        portDefinitions = PortDefinitions(0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")), config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatches should have size 3
      res.scalarMatch(Resource.CPUS).get.consumed.toSet should be(
        Set(
          GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation)),
          GeneralScalarMatch.Consumption(1.0, "marathon", reservation = Some(cpuReservation2))
        )
      )

      res.scalarMatch(Resource.MEM).get.consumed.toSet should be(
        Set(
          GeneralScalarMatch.Consumption(128.0, ResourceRole.Unreserved, reservation = Some(memReservation))
        )
      )
      res.scalarMatch(Resource.DISK).get.consumed.toSet should be(
        Set(
          DiskResourceMatch.Consumption(
            2.0, ResourceRole.Unreserved, reservation = Some(diskReservation), DiskSource.root, None)
        )
      )

      res.portsMatch.hostPortsWithRole.toSet should be(
        Set(Some(PortsMatcher.PortWithRole(ResourceRole.Unreserved, 80, reservation = Some(portsReservation))))
      )
    }

    "dynamically reserved resources are NOT matched if they have known labels" in {
      val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal")
      val cpuReservation2 = MarathonTestHelper.reservation(principal = "cpuPrincipal")
      val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = TaskLabels.labelsForTask(FrameworkId("foo"), Task.Id("bar")).labels)
      val diskReservation = MarathonTestHelper.reservation(principal = "memPrincipal")
      val portsReservation = MarathonTestHelper.reservation(principal = "portPrincipal")

      val offer =
        MarathonTestHelper.makeBasicOffer(role = "marathon")
          .clearResources()
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation2)))
          .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
          .addResources(MarathonTestHelper.scalarResource("disk", 2, reservation = Some(diskReservation)))
          .addResources(MarathonTestHelper.portsResource(80, 80, reservation = Some(portsReservation)))
          .build()

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 2.0, mem = 128.0, disk = 2.0),
        portDefinitions = PortDefinitions(0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.any(Set(ResourceRole.Unreserved, "marathon")), config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "ResourceSelector.reservedWithLabels should not match disk resource without label" in {
      val cpuReservation = MarathonTestHelper.reservation(principal = "cpuPrincipal", labels = Map("some" -> "label"))
      val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("some" -> "label"))

      val offer =
        MarathonTestHelper.makeBasicOffer(role = "marathon")
          .clearResources()
          .addResources(MarathonTestHelper.scalarResource("cpus", 1.0, role = "marathon", reservation = Some(cpuReservation)))
          .addResources(MarathonTestHelper.scalarResource("mem", 128.0, reservation = Some(memReservation)))
          .addResources(MarathonTestHelper.reservedDisk(id = "disk", size = 1024.0))
          .build()

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 2.0),
        portDefinitions = PortDefinitions()
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.reservedWithLabels(Set(ResourceRole.Unreserved, "marathon"), Map("some" -> "label")), config, Seq.empty
      )

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources success with preserved roles" in {
      val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), ResourceSelector.any(Set("marathon")), config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      val res = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch

      res.scalarMatch(Resource.CPUS).get.roles should be(Seq("marathon"))
      res.scalarMatch(Resource.MEM).get.roles should be(Seq("marathon"))
      res.scalarMatch(Resource.DISK) should be(empty)
    }

    "match resources failure because of incorrect roles" in {
      val offer = MarathonTestHelper.makeBasicOffer(role = "marathon").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer, app,
        knownInstances = Seq(), unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources success with constraints" in {
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        constraints = Set(
          Constraint.newBuilder
            .setField("hostname")
            .setOperator(Operator.LIKE)
            .setValue("host1")
            .build()
        )
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse should not be a[ResourceMatchResponse.NoMatch]
    }

    "match resources fails on constraints" in {
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        constraints = Set(
          Constraint.newBuilder
            .setField("hostname")
            .setOperator(Operator.LIKE)
            .setValue("host2")
            .build()
        )
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources fail on cpu" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.1).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources fail on mem" in {
      val offer = MarathonTestHelper.makeBasicOffer(mem = 0.1).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources should always match constraints and therefore return NoOfferMatchReason.UnfulfilledConstraint in case of no match" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.5).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0), // cpu does not match
        constraints = Set(
          Constraint.newBuilder.setField("test") // and constraint does not match
            .setOperator(Operator.LIKE)
            .setValue("test")
            .build()
        )
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should contain(NoOfferMatchReason.UnfulfilledConstraint)
      noMatch.reasons should contain(NoOfferMatchReason.InsufficientCpus)
    }

    "match resources fail on disk" in {
      val offer = MarathonTestHelper.makeBasicOffer(disk = 0.1).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 1.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match resources fail on ports" in {
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(1, 2)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "resource matcher should not respond with NoOfferMatchReason.UnfulfilledRole if role matches" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.5, role = "A").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0), // make sure it mismatches
        acceptedResourceRoles = Set("A", "B")
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, ResourceSelector.any(Set("A", "B")), config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should not contain NoOfferMatchReason.UnfulfilledRole
    }

    "resource matcher should respond with NoOfferMatchReason.UnfulfilledRole if runSpec requires unreserved Role but resources are reserved" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.5, role = "A").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0), // make sure it mismatches
        acceptedResourceRoles = Set(ResourceRole.Unreserved)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should contain(NoOfferMatchReason.UnfulfilledRole)
    }

    "resource matcher should respond with NoOfferMatchReason.UnfulfilledRole if runSpec has no role defined" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.5, role = "A").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0) // make sure it mismatches
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should contain(NoOfferMatchReason.UnfulfilledRole)
    }

    "resource matcher should respond with NoOfferMatchReason.UnfulfilledRole if role mismatches and offer contains other role" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 0.5, role = "C").build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0), // make sure it mismatches
        acceptedResourceRoles = Set("A", "B")
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should contain(NoOfferMatchReason.UnfulfilledRole)
    }

    "resource matcher should respond with all NoOfferMatchReason.Insufficient{Cpus, Memory, Gpus, Disk} if mismatches" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1, mem = 1, disk = 1, gpus = 1).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 2, mem = 2, disk = 2, gpus = 2) // make sure it mismatches
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should contain allOf (NoOfferMatchReason.InsufficientCpus, NoOfferMatchReason.InsufficientMemory,
        NoOfferMatchReason.InsufficientGpus, NoOfferMatchReason.InsufficientDisk)
    }

    "resource matcher should respond with NoOfferMatchReason.InsufficientPorts if ports mismatch and other requirements matches" in {
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1, mem = 1, disk = 1, beginPort = 0, endPort = 0).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1, mem = 1, disk = 1),
        portDefinitions = PortDefinitions(1, 2) // this match fails
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should be(Seq(NoOfferMatchReason.InsufficientPorts))
    }

    "resource matcher should not respond with NoOfferMatchReason.InsufficientPorts other requirements mismatches, even if port requirements mismatch" in {
      // NoOfferMatchReason.InsufficientPorts is calculated lazy and should only be calculated if all other requirements matches
      val offer = MarathonTestHelper.makeBasicOffer(cpus = 1, mem = 1, disk = 1, beginPort = 0, endPort = 0).build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 2, mem = 1, disk = 1), // this match fails
        portDefinitions = PortDefinitions(1, 2) // this would fail as well, but is not evaluated of the resource matcher
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, knownInstances = Seq.empty, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      val noMatch = resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch]

      noMatch.reasons should not contain NoOfferMatchReason.InsufficientPorts
    }

    "match resources success with constraints and old tasks in previous version" in {
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
        .addAttributes(TextAttribute("region", "pl-east"))
        .addAttributes(TextAttribute("zone", "pl-east-1b"))
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        versionInfo = OnlyVersion(Timestamp(2)),
        constraints = Set(
          Constraint.newBuilder
            .setField("region")
            .setOperator(Operator.GROUP_BY)
            .setValue("2")
            .build(),
          Constraint.newBuilder
            .setField("zone")
            .setOperator(Operator.GROUP_BY)
            .setValue("4")
            .build()
        )
      )
      val oldVersion = Timestamp(1)
      //We have 4 tasks spread across 2 DC and 3 zones
      //We want to launch new task (with  new version).
      //According to constraints it should be placed
      //in pl-east-1b
      val instances = Seq(

        instance("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
        instance("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
        instance("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

        instance("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
        instance("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, instances, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse should not be a[ResourceMatchResponse.NoMatch]
    }

    "match resources fail with constraints and old tasks deployed since last config change" in {
      val offer = MarathonTestHelper.makeBasicOffer(beginPort = 0, endPort = 0)
        .addAttributes(TextAttribute("region", "pl-east"))
        .addAttributes(TextAttribute("zone", "pl-east-1b"))
        .build()
      val oldVersion = Timestamp(1)
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        versionInfo = FullVersionInfo(
          version = Timestamp(5),
          lastScalingAt = Timestamp(5),
          lastConfigChangeAt = oldVersion
        ),
        constraints = Set(
          Constraint.newBuilder
            .setField("region")
            .setOperator(Operator.GROUP_BY)
            .setValue("2")
            .build(),
          Constraint.newBuilder
            .setField("zone")
            .setOperator(Operator.GROUP_BY)
            .setValue("4")
            .build()
        )
      )

      //We have 4 tasks spread across 2 DC and 3 zones
      //We want to scale our application.
      //But it will conflict with previously launched tasks.
      val instances = Seq(

        instance("1", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
        instance("2", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),
        instance("3", oldVersion, Map("region" -> "pl-east", "zone" -> "pl-east-1a")),

        instance("4", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1a")),
        instance("5", oldVersion, Map("region" -> "pl-west", "zone" -> "pl-west-1b"))
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(offer, app, instances, unreservedResourceSelector, config, Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "match disk won't allocate resources across disk different paths" in {
      val offerDisksTooSmall = MarathonTestHelper.makeBasicOffer().
        addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
          disk = Some(MarathonTestHelper.pathDisk("/path1")))).
        addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
          disk = Some(MarathonTestHelper.pathDisk("/path2")))).
        build()

      val offerSufficeWithMultOffers =
        offerDisksTooSmall.toBuilder.
          // add another resource for /path2, in addition to the resources from the previous offer
          addResources(MarathonTestHelper.scalarResource("disk", 500,
            disk = Some(MarathonTestHelper.pathDisk("/path2")))).
          build()

      val volume = PersistentVolume(
        containerPath = "/var/lib/data",
        mode = Mesos.Volume.Mode.RW,
        persistent = PersistentVolumeInfo(
          size = 1500,
          `type` = DiskType.Path))

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(
          cpus = 1.0,
          mem = 128.0,
          disk = 0.0
        ),
        container = Some(Container.Mesos(
          volumes = List(volume))),
        versionInfo = OnlyVersion(Timestamp(2)))

      ResourceMatcher.matchResources(
        offerDisksTooSmall, app,
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty) shouldBe a[ResourceMatchResponse.NoMatch]

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offerSufficeWithMultOffers, app,
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config,
        Seq.empty)

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
      resourceMatchResponse.asInstanceOf[ResourceMatchResponse.Match].resourceMatch.scalarMatch("disk").get.consumed.toSet shouldBe Set(
        DiskResourceMatch.Consumption(1024.0, "*", None, DiskSource(DiskType.Path, Some("/path2")), Some(volume)),
        DiskResourceMatch.Consumption(476.0, "*", None, DiskSource(DiskType.Path, Some("/path2")), Some(volume)))
    }

    "match disk enforces constraints" in {
      val offers = Seq("/mnt/disk-a", "/mnt/disk-b").map { path =>
        path -> MarathonTestHelper.makeBasicOffer().
          addResources(MarathonTestHelper.scalarResource("disk", 1024.0,
            disk = Some(MarathonTestHelper.pathDisk(path)))).
          build()
      }.toMap

      val volume = PersistentVolume(
        containerPath = "/var/lib/data",
        mode = Mesos.Volume.Mode.RW,
        persistent = PersistentVolumeInfo(
          size = 500,
          `type` = DiskType.Path,
          constraints = Set(MarathonTestHelper.constraint("path", "LIKE", Some(".+disk-b")))))

      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(
          cpus = 1.0,
          mem = 128.0,
          disk = 0.0
        ),
        container = Some(Container.Mesos(
          volumes = List(volume))),
        versionInfo = OnlyVersion(Timestamp(2)))

      ResourceMatcher.matchResources(
        offers("/mnt/disk-a"), app,
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty) shouldBe a[ResourceMatchResponse.NoMatch]

      ResourceMatcher.matchResources(
        offers("/mnt/disk-b"), app,
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty) shouldBe a[ResourceMatchResponse.Match]
    }

    "mount disk enforces maxSize constraints" in {
      val offer =
        MarathonTestHelper.makeBasicOffer().
          addResources(
            MarathonTestHelper.scalarResource("disk", 1024.0,
              disk = Some(MarathonTestHelper.mountDisk("/mnt/disk1")))).
            build()

      def mountRequest(size: Long, maxSize: Option[Long]) = {
        val volume = PersistentVolume(
          containerPath = "/var/lib/data",
          mode = Mesos.Volume.Mode.RW,
          persistent = PersistentVolumeInfo(
            size = size,
            maxSize = maxSize,
            `type` = DiskType.Mount))

        val app = AppDefinition(
          id = "/test".toRootPath,
          resources = Resources(
            cpus = 1.0,
            mem = 128.0,
            disk = 0.0
          ),
          container = Some(Container.Mesos(
            volumes = List(volume))),
          versionInfo = OnlyVersion(Timestamp(2)))
        app
      }

      inside(ResourceMatcher.matchResources(
        offer, mountRequest(500, None),
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty)) {
        case matches: ResourceMatchResponse.Match =>
          matches.resourceMatch.scalarMatches.collectFirst {
            case m: DiskResourceMatch =>
              (m.consumedValue, m.consumed.head.persistentVolume.get.persistent.size)
          } shouldBe Some((1024, 1024))
      }

      ResourceMatcher.matchResources(
        offer, mountRequest(500, Some(750)),
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty) shouldBe a[ResourceMatchResponse.NoMatch]

      ResourceMatcher.matchResources(
        offer, mountRequest(500, Some(1024)),
        knownInstances = Seq(),
        ResourceSelector.reservable,
        config, Seq.empty) shouldBe a[ResourceMatchResponse.Match]
    }

    "a Reserved instance prevents creation of another reservation when hostname constraint is set" in {
      val offer = MarathonTestHelper.makeBasicOffer().
        addResources(MarathonTestHelper.scalarResource("disk", 1024.0)).
        setHostname(AgentTestDefaults.defaultHostName).
        build()

      val volume = PersistentVolume(
        containerPath = "/var/data",
        mode = Mesos.Volume.Mode.RW,
        persistent = PersistentVolumeInfo(
          size = 500, `type` = DiskType.Root))

      val app = AppDefinition(
        id = "/test-persistent-volumes-with-unique-constraint".toRootPath,
        instances = 3,
        resources = Resources(cpus = 0.1, mem = 32.0, disk = 0.0),
        constraints = Set(Constraint.newBuilder.setField("hostname").
          setOperator(Constraint.Operator.UNIQUE).build),
        container = Some(Container.Mesos(
          volumes = List(volume))))

      // Since offer matcher checks the instance version it's should be >= app.version
      val instance = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskReserved(
        Task.LocalVolumeId(app.id, volume))
        .getInstance()

      val response = ResourceMatcher.matchResources(
        offer, app, knownInstances = Seq(instance), ResourceSelector.reservable,
        config, Seq.empty)

      response shouldBe a[ResourceMatchResponse.NoMatch]
    }

    "a Reserved instance DOES NOT prevent creation of another reservation when NO hostname constraint is set" in {
      val offer = MarathonTestHelper.makeBasicOffer().
        addResources(MarathonTestHelper.scalarResource("disk", 1024.0)).
        setHostname(AgentTestDefaults.defaultHostName).
        build()

      val volume = PersistentVolume(
        containerPath = "/var/data",
        mode = Mesos.Volume.Mode.RW,
        persistent = PersistentVolumeInfo(
          size = 500, `type` = DiskType.Root))

      val app = AppDefinition(
        id = "/test-persistent-volumes-without-unique-constraint".toRootPath,
        instances = 3,
        resources = Resources(cpus = 0.1, mem = 32.0, disk = 0.0),
        container = Some(Container.Mesos(
          volumes = List(volume))))

      // Since offer matcher checks the instance version it's should be >= app.version
      val instance = TestInstanceBuilder.newBuilder(app.id, version = app.version).addTaskReserved(
        Task.LocalVolumeId(app.id, volume))
        .getInstance()

      val response = ResourceMatcher.matchResources(
        offer, app, knownInstances = Seq(instance), ResourceSelector.reservable,
        config, Seq.empty)

      response shouldBe a[ResourceMatchResponse.Match]
    }

    "match any offer on gpu-enabled agent with a default gpu scheduling behavior" in {
      val gpuConfig = AllConf.withTestConfig()
      val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val gpuApp = AppDefinition(
        id = "/gpu".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0, gpus = 1),
        portDefinitions = PortDefinitions(0, 0)
      )

      val nonGpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      nonGpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]

      val gpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        gpuApp,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      gpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
    }

    "match any offer on gpu-enabled agent with a undefined gpu scheduling behavior" in {
      val gpuConfig = AllConf.withTestConfig("--draining_seconds", "300", "--gpu_scheduling_behavior", "undefined")
      val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val gpuApp = AppDefinition(
        id = "/gpu".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0, gpus = 1),
        portDefinitions = PortDefinitions(0, 0)
      )

      val nonGpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      nonGpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]

      val gpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        gpuApp,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      gpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
    }

    "match any offer on gpu-enabled agent with a unrestricted gpu scheduling behavior" in {
      val gpuConfig = AllConf.withTestConfig(
        "--draining_seconds", "300",
        "--gpu_scheduling_behavior", "unrestricted",
        "--enable_features", "gpu_resources")
      val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val gpuApp = AppDefinition(
        id = "/gpu".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0, gpus = 1),
        portDefinitions = PortDefinitions(0, 0)
      )

      val nonGpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      nonGpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]

      val gpuResourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        gpuApp,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      gpuResourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
    }

    "not match an offer on gpu-enabled agent with a restricted gpu scheduling behavior if GPU is not required by app" in {
      val gpuConfig = AllConf.withTestConfig(
        "--draining_seconds", "300",
        "--gpu_scheduling_behavior", "restricted",
        "--enable_features", "gpu_resources")
      val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch].reasons.head shouldEqual DeclinedScarceResources
    }

    "match an offer on gpu-enabled agent with a restricted gpu scheduling behavior if GPU is required by app" in {
      val gpuConfig = AllConf.withTestConfig(
        "--draining_seconds", "300",
        "--gpu_scheduling_behavior", "restricted",
        "--enable_features", "gpu_resources")
      val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
        .build()
      val app = AppDefinition(
        id = "/test".toRootPath,
        resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0, gpus = 2),
        portDefinitions = PortDefinitions(0, 0)
      )

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty
      )

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
    }

    "match an offer on gpu-enabled agent with a restricted gpu scheduling behavior if GPU is not required by app but there is a Persistent Volume" in {
      val gpuConfig = AllConf.withTestConfig(
        "--draining_seconds", "300",
        "--gpu_scheduling_behavior", "restricted",
        "--enable_features", "gpu_resources")

      val app = MarathonTestHelper.appWithPersistentVolume()
      val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(localVolumeId).getInstance()

      val taskId = instance.appTask.taskId

      val basicOffer = MarathonTestHelper.makeBasicOffer(gpus = 4)

      val offer = MarathonTestHelper.addVolumesToOffer(basicOffer, taskId, localVolumeId).build()

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty,
        reservedInstances = Seq(instance)
      )

      resourceMatchResponse shouldBe a[ResourceMatchResponse.Match]
    }

    "not match an offer on gpu-enabled agent with a restricted gpu scheduling behavior if GPU is not required by app and we want to reserve a new Persistent Volume" in {
      val gpuConfig = AllConf.withTestConfig(
        "--draining_seconds", "300",
        "--gpu_scheduling_behavior", "restricted",
        "--enable_features", "gpu_resources")

      val app = MarathonTestHelper.appWithPersistentVolume()
      val localVolumeId = Task.LocalVolumeId(app.id, "persistent-volume", "uuid")
      val instance = TestInstanceBuilder.newBuilder(app.id).addTaskReserved(localVolumeId).getInstance()

      val taskId = instance.appTask.taskId

      val basicOffer = MarathonTestHelper.makeBasicOffer(gpus = 4)

      val offer = MarathonTestHelper.addVolumesToOffer(basicOffer, taskId, localVolumeId).build()

      val resourceMatchResponse = ResourceMatcher.matchResources(
        offer,
        app,
        knownInstances = Seq.empty,
        unreservedResourceSelector,
        gpuConfig,
        Seq.empty,
        reservedInstances = Seq.empty
      )

      resourceMatchResponse shouldBe a[ResourceMatchResponse.NoMatch]
      resourceMatchResponse.asInstanceOf[ResourceMatchResponse.NoMatch].reasons.head shouldEqual DeclinedScarceResources
    }

  }

  "ResourceMatcher" should {

    val overrideCases = Table(
      ("gpu_scheduling_behavior", "GPU_SCHEDULING_BEHAVIOR", "expected"),
      ("unrestricted", Some("restricted"), "NoMatch"),
      ("unrestricted", None, "Match"),
      ("restricted", Some("unrestricted"), "Match"),
      ("restricted", None, "NoMatch")
    )

    forAll(overrideCases) { (gpuSchedulingBehavior, overrideLabel, expected) =>

      s"return a $expected in case of ${overrideLabel.getOrElse("no")} override of $gpuSchedulingBehavior behavior and no Persistent Volume involved" in {

        val gpuConfig = AllConf.withTestConfig(
          "--draining_seconds", "300",
          "--gpu_scheduling_behavior", gpuSchedulingBehavior,
          "--enable_features", "gpu_resources")
        val offer = MarathonTestHelper.makeBasicOffer(gpus = 4)
          .build()
        val app = AppDefinition(
          id = "/test".toRootPath,
          resources = Resources(cpus = 1.0, mem = 128.0, disk = 0.0),
          portDefinitions = PortDefinitions(0, 0),

          labels = overrideLabel.map(label => Map("GPU_SCHEDULING_BEHAVIOR" -> label)).getOrElse(Map.empty)
        )

        val resourceMatchResponse = ResourceMatcher.matchResources(
          offer,
          app,
          knownInstances = Seq.empty,
          unreservedResourceSelector,
          gpuConfig,
          Seq.empty
        )

        def getObjectName(fqcn: String) = fqcn.reverse.takeWhile(_ != '$').reverse

        getObjectName(resourceMatchResponse.getClass.getName) shouldEqual expected
      }
    }
  }

  val appId = PathId("/test")
  def instance(id: String, version: Timestamp, attrs: Map[String, String]): Instance = { // linter:ignore:UnusedParameter
    val attributes: Seq[Attribute] = attrs.map {
      case (name, v) => TextAttribute(name, v): Attribute
    }(collection.breakOut)
    TestInstanceBuilder.newBuilder(appId, version = version).addTaskWithBuilder().taskStaged()
      .build()
      .withAgentInfo(attributes = Some(attributes))
      .getInstance()
  }

  lazy val unreservedResourceSelector = ResourceSelector.any(Set(ResourceRole.Unreserved))
}
