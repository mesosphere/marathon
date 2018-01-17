package mesosphere.marathon
package core.instance

import mesosphere.UnitTest

class ReservationIdTest extends UnitTest {
  "ReservationId" should {
    "have .toString working properly" in {
      val reservationId = Reservation.Id("app", ".", None, "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationId.toString shouldEqual "app.4455cb85-0c16-490d-b84e-481f8321ff0a"
      val reservationIdTwo = Reservation.Id("app", ".", Some("marathon-"), "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationIdTwo.toString shouldEqual "app.marathon-4455cb85-0c16-490d-b84e-481f8321ff0a"
      val reservationIdThree = Reservation.Id("app", ".", Some("instance-"), "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationIdThree.toString shouldEqual "app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a"
    }

    "have .instanceId working properly" in {
      val reservationId = Reservation.Id("app", ".", None, "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationId.instanceId.idString shouldEqual "app.marathon-4455cb85-0c16-490d-b84e-481f8321ff0a"
      val reservationIdTwo = Reservation.Id("app", ".", Some("marathon-"), "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationIdTwo.instanceId.idString shouldEqual "app.marathon-4455cb85-0c16-490d-b84e-481f8321ff0a"
      val reservationIdThree = Reservation.Id("app", ".", Some("instance-"), "4455cb85-0c16-490d-b84e-481f8321ff0a")
      reservationIdThree.instanceId.idString shouldEqual "app.instance-4455cb85-0c16-490d-b84e-481f8321ff0a"
    }
  }
}
