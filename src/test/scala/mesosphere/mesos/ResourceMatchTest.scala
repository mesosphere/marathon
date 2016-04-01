package mesosphere.mesos

import mesosphere.marathon.MarathonTestHelper
import mesosphere.marathon.tasks.{ PortsMatcher, PortsMatch }
import org.scalatest.{ Matchers, GivenWhenThen, FunSuite }
import scala.collection.immutable.Seq

class ResourceMatchTest
    extends FunSuite with GivenWhenThen with Matchers {
  test("resources include all matched reservations") {
    Given("a resource match with reservations")
    val memReservation = MarathonTestHelper.reservation(principal = "memPrincipal", labels = Map("resource" -> "mem"))
    val portReservation = MarathonTestHelper.reservation(principal = "portPrincipal", labels = Map("resource" -> "ports"))

    val resourceMatch = ResourceMatcher.ResourceMatch(
      scalarMatches = Iterable(
        ScalarMatch(
          "mem", 128.0,
          consumed = Iterable(ScalarMatch.Consumption(128.0, "role1", reservation = Some(memReservation))),
          scope = ScalarMatchResult.Scope.NoneDisk
        )
      ),
      portsMatch = PortsMatch(Seq(PortsMatcher.PortWithRole("role2", 80, reservation = Some(portReservation))))
    )

    When("converting it to resources")
    val resources = resourceMatch.resources

    Then("the resources should refer to the reservations")
    resources should equal(
      Iterable(
        MarathonTestHelper.scalarResource("mem", 128, "role1", reservation = Some(memReservation)),
        MarathonTestHelper.portsResource(80, 80, "role2", reservation = Some(portReservation))
      )
    )
  }
}
