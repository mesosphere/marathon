package mesosphere.mesos

import mesosphere.UnitTest
import mesosphere.marathon.tasks.{ PortsMatch, PortsMatcher }
import mesosphere.marathon.test.MarathonTestHelper

import scala.collection.immutable.Seq

class ResourceMatchTest extends UnitTest {
  "ResourceMatch" should {
    "resources include all matched reservations" in {
      Given("a resource match with reservations")
      val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("resource" -> "mem"))
      val portReservation = MarathonTestHelper.reservation(principal = "portPrincipal", labels = Map("resource" -> "ports"))

      val resourceMatch = ResourceMatcher.ResourceMatch(
        scalarMatches = Seq(
          GeneralScalarMatch(
            "mem", 128.0,
            consumed = Seq(GeneralScalarMatch.Consumption(128.0, "role1", None, reservation = Some(memReservation))),
            scope = ScalarMatchResult.Scope.NoneDisk
          )
        ),
        portsMatch = PortsMatch(Seq(Some(PortsMatcher.PortWithRole("role2", 80, reservation = Some(portReservation)))))
      )

      When("converting it to resources")
      val resources = resourceMatch.resources

      Then("the resources should refer to the reservations")
      resources should equal(
        Seq(
          MarathonTestHelper.scalarResource("mem", 128, "role1", reservation = Some(memReservation)),
          MarathonTestHelper.portsResource(80, 80, "role2", reservation = Some(portReservation))
        )
      )
    }
  }
}
