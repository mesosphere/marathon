package mesosphere.mesos

import mesosphere.marathon.MarathonSpec
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.state.AppDefinition
import mesosphere.marathon.state.CustomResource
import mesosphere.marathon.state.CustomResource.{ CustomScalar, CustomRange, CustomRanges, CustomSet }
import mesosphere.marathon.state.PathId._
import org.scalatest.Matchers

import scala.collection.immutable.Seq

class ResourceMatcherTest extends MarathonSpec with Matchers {
  test("match resources success") {
    val offer = makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 0.0,
      ports = Seq(0, 0),
      customResources = Map("customScalar" -> CustomResource(Some(CustomScalar(3.0))),
        "customSet" -> CustomResource(set = Some(CustomSet(Set("a", "b", "c"), 3))),
        "customRanges" -> CustomResource(ranges = Some(CustomRanges(Seq(CustomRange(10L, Some(25000L), Some(32000L))))))
      )
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    println("TODOC-matcher")
    println(offer)
    println(app)

    resOpt should not be empty
    val res = resOpt.get

    println("TODOC-matcher")
    println(res)

    res.cpuRole should be("*")
    res.memRole should be("*")
    res.diskRole should be("*")

    // TODOC
    //    res.customScalars.last.getValue should be (3.0)
    //    res.customSets.last.getItemList should be Seq("a", "b", "c")

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
    res.diskRole should be("marathon")
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

  test("match custom resources fails on custom scalar resource") {
    val offer = makeBasicOffer(customScalar = 3.0).build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      ports = Seq(0, 0),
      customResources = Map("customScalar" -> CustomResource(scalar = Some(CustomScalar(4.0))))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match custom resources fails on custom set resource") {
    val offer = makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      ports = Seq(0, 0),
      customResources = Map("customSet" -> CustomResource(set = Some(CustomSet(Set("a", "e"), 2))))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match custom resources fails on custom ranges resource") {
    val offer = makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      ports = Seq(0, 0),
      customResources = Map("customRanges" -> CustomResource(ranges =
        Some(CustomRanges(Seq(CustomRange(10000L, Some(15000L), Some(25000L)))))))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should be (empty)
  }

  test("match custom resources for range passes") {
    val offer = makeBasicOffer().build()
    val app = AppDefinition(
      id = "/test".toRootPath,
      cpus = 1.0,
      mem = 128.0,
      disk = 1.0,
      ports = Seq(0, 0),
      customResources = Map("customRanges" -> CustomResource(ranges =
        Some(CustomRanges(Seq(CustomRange(10L, Some(19995L), Some(25005L)))))))
    )

    val resOpt = ResourceMatcher.matchResources(offer, app, Set())

    resOpt should not be (empty)
  }
}
