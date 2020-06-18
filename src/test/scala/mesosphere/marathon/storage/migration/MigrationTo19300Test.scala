package mesosphere.marathon
package storage.migration

import java.time.OffsetDateTime

import akka.stream.scaladsl.{Sink, Source}
import mesosphere.AkkaUnitTest
import org.apache.mesos.{Protos => Mesos}

import scala.collection.JavaConverters._

class MigrationTo19300Test extends AkkaUnitTest {
  "migrating apps" should {
    def mockServiceDefinition(
        id: String = "/serious-business",
        role: String,
        acceptedResourceRoles: Seq[String]
    ): Protos.ServiceDefinition = {
      val resourceRoles =
        if (acceptedResourceRoles.isEmpty)
          None
        else
          Some(Protos.ResourceRoles.newBuilder.addAllRole(acceptedResourceRoles.asJava))
      val b = Protos.ServiceDefinition
        .newBuilder()
        .setId(id)
        .setRole(role)
        .setCmd(Mesos.CommandInfo.newBuilder().setValue("sleep 3600").build)
        .setInstances(1)
        .setExecutor("//cmd")

      resourceRoles.foreach(b.setAcceptedResourceRoles)
      b.build
    }

    "it removes invalid roles from apps" in {
      val service = mockServiceDefinition(role = "role", acceptedResourceRoles = Seq("a", "role", "*"))
      val time = OffsetDateTime.now()

      val Seq((migratedService, Some(migratedTime))) =
        Source.single((service, Some(time))).via(MigrationTo19300.appMigratingFlow).runWith(Sink.seq).futureValue

      migratedService.getAcceptedResourceRoles.getRoleList.asScala shouldBe Seq("role", "*")
      migratedTime shouldBe time
    }

    "it defaults to [*] if all roles were invalid" in {
      val service = mockServiceDefinition(role = "test", acceptedResourceRoles = Seq("a", "b", "c"))
      val time = OffsetDateTime.now()

      val Seq((migratedService, Some(migratedTime))) =
        Source.single((service, Some(time))).via(MigrationTo19300.appMigratingFlow).runWith(Sink.seq).futureValue

      migratedService.getAcceptedResourceRoles.getRoleList.asScala shouldBe Seq("*")
      migratedTime shouldBe time
    }

    "it leaves apps alone if they are valid" in {
      val service = mockServiceDefinition(role = "test", acceptedResourceRoles = Seq("*"))

      Source
        .single((service, None))
        .via(MigrationTo19300.appMigratingFlow)
        .runWith(Sink.seq)
        .futureValue
        .shouldBe(empty)
    }
  }

  "migrating pods" should {
    def mockPod(id: String = "/serious-business", role: String, acceptedResourceRoles: Seq[String]): raml.Pod = {

      val scheduling = if (acceptedResourceRoles.nonEmpty) {
        val placement = raml.PodPlacementPolicy(acceptedResourceRoles = acceptedResourceRoles)
        Some(raml.PodSchedulingPolicy(placement = Some(placement)))
      } else {
        None
      }
      raml.Pod(id, containers = Nil, role = Some(role), scheduling = scheduling)
    }

    def getAcceptedResourceRoles(pod: raml.Pod): Option[Seq[String]] =
      for {
        scheduling <- pod.scheduling
        placement <- scheduling.placement
      } yield placement.acceptedResourceRoles

    "it removes invalid roles" in {
      val service = mockPod(role = "role", acceptedResourceRoles = Seq("a", "role", "*"))
      val time = OffsetDateTime.now()

      val Seq((migratedPod, Some(migratedTime))) =
        Source.single((service, Some(time))).via(MigrationTo19300.podMigratingFlow).runWith(Sink.seq).futureValue

      getAcceptedResourceRoles(migratedPod) shouldBe Some(Seq("role", "*"))
      migratedTime shouldBe time
    }

    "it defaults to [*] if all roles were invalid" in {
      val service = mockPod(role = "test", acceptedResourceRoles = Seq("a", "b", "c"))
      val time = OffsetDateTime.now()

      val Seq((migratedService, Some(migratedTime))) =
        Source.single((service, Some(time))).via(MigrationTo19300.podMigratingFlow).runWith(Sink.seq).futureValue

      getAcceptedResourceRoles(migratedService) shouldBe Some(Seq("*"))
      migratedTime shouldBe time
    }

    "it does not modify valid entities" in {
      val service = mockPod(role = "test", acceptedResourceRoles = Seq("*"))

      Source
        .single((service, None))
        .via(MigrationTo19300.podMigratingFlow)
        .runWith(Sink.seq)
        .futureValue
        .shouldBe(empty)
    }
  }
}
