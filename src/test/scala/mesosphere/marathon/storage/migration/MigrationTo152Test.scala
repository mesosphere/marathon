package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.scaladsl.Source
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.instance.{ Instance, TestInstanceBuilder }
import mesosphere.marathon.state._
import mesosphere.marathon.storage.migration.MigrationTo146.Environment
import mesosphere.marathon.storage.repository.InstanceRepository
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }

class MigrationTo152Test extends AkkaUnitTest with GroupCreation with StrictLogging {

  "Migration to 1.5.2" should {
    "do nothing if env var is not configured" in new Fixture {
      MigrationTo152.migrateUnreachableInstances(instanceRepository)(env, ctx, mat).futureValue
      verify(instanceRepository, never).all()
      verify(instanceRepository, never).store(_: Instance)
    }

    "do migration if env var is configured" in new Fixture(Map(MigrationTo146.MigrateUnreachableStrategyEnvVar -> "true")) {
      MigrationTo152.migrateUnreachableInstances(instanceRepository)(env, ctx, mat).futureValue
      val targetInstance = instance.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 5.seconds)) // case 2
      val targetInstance2 = instance2.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 0.seconds)) // case 1
      val targetInstance3 = instance3.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 0.seconds)) // case 3

      logger.info(s"Migration instances ($instance, $instance2, $instance3) ")
      verify(instanceRepository, once).all()
      verify(instanceRepository, once).store(targetInstance)
      verify(instanceRepository, once).store(targetInstance2)
      verify(instanceRepository, once).store(targetInstance3)
    }
  }

  private class Fixture(val environment: Map[String, String] = Map.empty) {
    val instanceRepository: InstanceRepository = mock[InstanceRepository]
    implicit lazy val env = Environment(environment)
    implicit lazy val mat: Materializer = ActorMaterializer()
    implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
    val instance = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(PathId("/app"))).copy(unreachableStrategy = UnreachableEnabled(1.seconds, 5.seconds))
    val instance2 = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(PathId("/app2"))).copy(unreachableStrategy = UnreachableEnabled(5.minutes, 10.minutes))
    val instance3 = TestInstanceBuilder.emptyInstance(instanceId = Instance.Id.forRunSpec(PathId("/app3"))).copy(unreachableStrategy = UnreachableEnabled(1.seconds, 2.seconds))
    instanceRepository.all() returns Source(Seq(instance, instance2, instance3))
    instanceRepository.store(any) returns Future.successful(Done)
  }

}
