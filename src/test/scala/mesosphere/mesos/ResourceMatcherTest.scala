package mesosphere.mesos

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.PathId._
import org.scalatest.Matchers

import scala.collection.immutable.Seq

class ResourceMatcherTest extends MarathonSpec with Matchers {
  test("match with app.disk == 0, even if no disk resource is contained in the offer") {
    import scala.collection.JavaConverters._
    val offerBuilder = makeBasicOffer()
    val diskResourceIndex = offerBuilder.getResourcesList.asScala.indexWhere(_.getName == "disk")
    offerBuilder.removeResources(diskResourceIndex)
    val offer = offerBuilder.build()

    offer.getResourcesList.asScala.find(_.getName == "disk") should be('empty)

    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should not be empty
    val res = resOpt.get

    res.cpuRole should be("*")
    res.memRole should be("*")
    res.diskRole should be("")

    // check if we got 2 ports
    val range = res.ports.head.ranges.head
    (range.end - range.begin) should be (1)
  }

  test("match resources success") {
    val offer = makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should not be empty
    val res = resOpt.get

    res.cpuRole should be("*")
    res.memRole should be("*")
    res.diskRole should be("")

    // check if we got 2 ports
    val range = res.ports.head.ranges.head
    (range.end - range.begin) should be (1)
  }

  test("match resources success with preserved roles") {
    val offer = makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), acceptedResourceRoles = Set("marathon"))

    resOpt should not be empty
    val res = resOpt.get

    res.cpuRole should be("marathon")
    res.memRole should be("marathon")
    res.diskRole should be("")
  }

  test("match resources failure because of incorrect roles") {
    val offer = makeBasicOffer(role = "marathon").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(
      offer, app,
      runningTasks = Set(), acceptedResourceRoles = Set("*"))

    resOpt should be ('empty)
  }

  test("match resources success with constraints") {
    val offer = makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host1")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should not be empty
  }

  test("match resources fails on constraints") {
    val offer = makeBasicOffer(beginPort = 0, endPort = 0).setHostname("host1").build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      constraints = Set(
        Constraint.newBuilder
          .setField("hostname")
          .setOperator(Operator.LIKE)
          .setValue("host2")
          .build()
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match resources fail on cpu") {
    val offer = makeBasicOffer(cpus = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match resources fail on mem") {
    val offer = makeBasicOffer(mem = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match resources fail on disk") {
    val offer = makeBasicOffer(disk = 0.1).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      ports = Seq(0, 0)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match resources fail on ports") {
    val offer = makeBasicOffer(beginPort = 0, endPort = 0).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(1, 2)
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }
}
