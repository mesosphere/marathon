package mesosphere.marathon
package raml

import mesosphere.UnitTest
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.mesos.protos.{ ScalarResource, TextAttribute }
import org.apache.mesos.{ Protos => Mesos }

class OfferConversionTest extends UnitTest {
  "OfferConversion" should {
    "A scala value is converted correctly" in {
      Given("A scalar value")
      val scalar = Mesos.Value.Scalar.newBuilder().setValue(123L).build()

      When("The value is converted to raml")
      val raml = scalar.toRaml[Option[Double]]

      Then("The value is converted correctly")
      raml should be (defined)
      raml should be (Some(123L))
    }

    "A Range value is converted correctly" in {
      Given("A range value")
      val range = Mesos.Value.Range.newBuilder().setBegin(0).setEnd(5).build()

      When("The value is converted to raml")
      val raml = range.toRaml[NumberRange]

      Then("The value is converted correctly")
      raml should be (NumberRange(0L, 5L))
    }

    "An OfferResource is converted correctly" in {
      Given("An offer resource")
      import mesosphere.mesos.protos.Implicits._
      val resource: Mesos.Resource = ScalarResource("cpus", 34L)

      When("The value is converted to raml")
      val raml = resource.toRaml[OfferResource]

      Then("The value is converted correctly")
      raml.name should be ("cpus")
      raml.role should be ("*")
      raml.scalar should be (Some(34L))
      raml.ranges should be (empty)
      raml.set should be (empty)
    }

    "An Offer Attribute is converted correctly" in {
      Given("An offer attribute")
      import mesosphere.mesos.protos.Implicits._
      val attribute: Mesos.Attribute = TextAttribute("key", "value")

      When("The value is converted to raml")
      val raml = attribute.toRaml[AgentAttribute]

      Then("The value is converted correctly")
      raml.name should be ("key")
      raml.scalar should be (empty)
      raml.text should be (Some("value"))
      raml.ranges should be (empty)
      raml.set should be (empty)
    }

    "An Offer is converted correctly" in {
      Given("An offer")
      val offer = MarathonTestHelper.makeBasicOffer().build()

      When("The value is converted to raml")
      val raml = offer.toRaml[Offer]

      Then("The value is converted correctly")
      raml.agentId should be (offer.getSlaveId.getValue)
      raml.attributes should have size 0
      raml.hostname should be (offer.getHostname)
      raml.id should be (offer.getId.getValue)
      raml.resources should have size 5
    }
  }
}
