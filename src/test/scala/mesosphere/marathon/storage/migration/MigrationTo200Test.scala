package mesosphere.marathon
package storage.migration

import java.time.{ OffsetDateTime, ZoneOffset }

import akka.Done
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.Source
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance.Id
import mesosphere.marathon.core.instance.{ Instance, Reservation, TestInstanceBuilder }
import mesosphere.marathon.core.storage.store.{ IdResolver, PersistenceStore }
import mesosphere.marathon.core.storage.store.impl.zk.{ ZkId, ZkPersistenceStore, ZkSerialized }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.test.GroupCreation
import org.apache.mesos
import org.apache.mesos.Protos.NetworkInfo.Protocol
import play.api.libs.json.{ JsValue, Json }
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.Try

class MigrationTo200Test extends AkkaUnitTest with GroupCreation with StrictLogging {

  "Migration to 1.6.0" should {
    "do migration for instances with tasks with reservations" in new Fixture {
      initMocks()
      MigrationTo200.migrateReservations(instanceRepository, persistenceStore)(ctx, mat).futureValue
      val targetInstance = instance.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))
      val targetInstance2 = instance2.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))
      val targetInstance3 = instance3.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))

      logger.info(s"Migration instances ($instance, $instance2, $instance3) ")
      verify(instanceRepository, once).ids()
      verify(instanceRepository, once).store(targetInstance)
      verify(instanceRepository, once).store(targetInstance2)
      verify(instanceRepository, once).store(targetInstance3)
    }

    "don't change instances without reservations" in new Fixture {
      override val instance3 = TestInstanceBuilder.emptyInstance(instanceId = instanceId3).copy(tasksMap = Map.empty)
      initMocks()
      MigrationTo200.migrateReservations(instanceRepository, persistenceStore)(ctx, mat).futureValue
      val targetInstance = instance.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))
      val targetInstance2 = instance2.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))
      val targetInstance3 = instance3

      logger.info(s"Migration instances ($instance, $instance2, $instance3) ")
      verify(instanceRepository, once).ids()
      verify(instanceRepository, once).store(targetInstance)
      verify(instanceRepository, once).store(targetInstance2)
      verify(instanceRepository, never).store(targetInstance3)
    }

    "don't change instances if reservation is already there" in new Fixture {
      override val instance = TestInstanceBuilder.emptyInstance(instanceId = instanceId1).copy(tasksMap = Map.empty, reservation = Some(Reservation(Nil, Reservation.State.New(None))))
      override val instance3 = TestInstanceBuilder.emptyInstance(instanceId = instanceId3).copy(reservation = Some(Reservation(Nil, Reservation.State.New(None))))
      initMocks()
      MigrationTo200.migrateReservations(instanceRepository, persistenceStore)(ctx, mat).futureValue
      val targetInstance = instance.copy(reservation = Some(Reservation(Nil, Reservation.State.New(None))))
      val targetInstance2 = instance2.copy(reservation = Some(Reservation(Nil, Reservation.State.Launched)))
      val targetInstance3 = instance3

      logger.info(s"Migration instances ($instance, $instance2, $instance3) ")
      verify(instanceRepository, once).ids()
      verify(instanceRepository, never).store(targetInstance)
      verify(instanceRepository, once).store(targetInstance2)
      verify(instanceRepository, never).store(targetInstance3)
    }
  }

  private class Fixture {

    val now = Timestamp(OffsetDateTime.of(2015, 2, 3, 12, 30, 0, 0, ZoneOffset.UTC))

    val instanceRepository: InstanceRepository = mock[InstanceRepository]
    val persistenceStore: ZkPersistenceStore = mock[ZkPersistenceStore]
    implicit lazy val mat: Materializer = ActorMaterializer()
    implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
    val instanceId1 = Instance.Id.forRunSpec(PathId("/app"))
    val instanceId2 = Instance.Id.forRunSpec(PathId("/app2"))
    val instanceId3 = Instance.Id.forRunSpec(PathId("/app3"))

    val taskMap = List(
      Task(
        Task.Id.forInstanceId(instanceId1, None),
        Timestamp.now(),
        Task.Status(
          stagedAt = Timestamp.now(),
          condition = Condition.Running,
          networkInfo = NetworkInfo(
            "127.0.0.1",
            8888 :: Nil,
            mesos.Protos.NetworkInfo.IPAddress.newBuilder()
              .setProtocol(Protocol.IPv4)
              .setIpAddress("127.0.0.1")
              .build() :: Nil)
        ))
    ).map(t => t.taskId -> t).toMap

    def legacyInstanceJson(i: Instance): JsValue = {
      val fieldsMap = Json.toJson(i).asInstanceOf[JsObject].value

      val res = JsObject(fieldsMap.map {
        case ("tasksMap", taskMap) =>
          val updatedTaskMap = "tasksMap" -> JsObject(taskMap.asInstanceOf[JsObject].value.mapValues {
            case task: JsObject =>
              task + ("reservation" -> Json.toJson(Reservation(Nil, Reservation.State.Launched)).asInstanceOf[JsObject])
          })

          updatedTaskMap

        case (k, v) => k -> v
      })

      res
    }

    def instance = TestInstanceBuilder.emptyInstance(now = now, instanceId = instanceId1).copy(tasksMap = taskMap)
    def instance2 = TestInstanceBuilder.emptyInstance(now = now, instanceId = instanceId2).copy(tasksMap = taskMap)
    def instance3 = TestInstanceBuilder.emptyInstance(now = now, instanceId = instanceId3).copy(tasksMap = taskMap)

    def initMocks() = {
      instanceRepository.ids() returns Source(List(instance, instance2, instance3).map(_.instanceId))
      persistenceStore.get[Instance.Id, JsValue](equalTo(instance.instanceId))(any, any) returns Future(Some(legacyInstanceJson(instance)))
      persistenceStore.get[Instance.Id, JsValue](equalTo(instance2.instanceId))(any, any) returns Future(Some(legacyInstanceJson(instance2)))
      persistenceStore.get[Instance.Id, JsValue](equalTo(instance3.instanceId))(any, any) returns Future(Some(legacyInstanceJson(instance3)))
      instanceRepository.store(any) returns Future.successful(Done)
    }
  }

}
