package mesosphere.marathon
package storage.migration

import akka.Done
import akka.stream.scaladsl.Source
import akka.stream.{ ActorMaterializer, Materializer }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._
import mesosphere.marathon.storage.migration.MigrationTo146.Environment
import mesosphere.marathon.storage.repository.{ AppRepository, PodRepository }
import mesosphere.marathon.test.GroupCreation

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }

class MigrationTo146Test extends AkkaUnitTest with GroupCreation with StrictLogging {

  "Migration to 1.4.6" should {
    "do nothing if env var is not configured" in new Fixture {
      MigrationTo146.migrateUnreachableApps(appRepository, podRepository)(env, ctx, mat).futureValue
      verify(appRepository, never).all()
      verify(appRepository, never).store(_: AppDefinition)
      verify(podRepository, never).all()
      verify(podRepository, never).store(_: PodDefinition)
    }

    "do migration if env var is configured" in new Fixture(Map(MigrationTo146.MigrateUnreachableStrategyEnvVar -> "true")) {
      MigrationTo146.migrateUnreachableApps(appRepository, podRepository)(env, ctx, mat).futureValue
      val targetApp = app.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 5.seconds)) // case 2
      val targetApp2 = app2.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 0.seconds)) // case 1
      val targetPod = pod.copy(unreachableStrategy = UnreachableEnabled(0.seconds, 0.seconds)) // case 3

      logger.info(s"Migration app ($app, $app2) and pod ($pod)")
      verify(appRepository, once).all()
      verify(appRepository, once).store(targetApp)
      verify(appRepository, once).store(targetApp2)

      verify(podRepository, once).all()
      verify(podRepository, once).store(targetPod)
    }
  }

  private class Fixture(val environment: Map[String, String] = Map.empty) {
    val appRepository: AppRepository = mock[AppRepository]
    val podRepository: PodRepository = mock[PodRepository]
    implicit lazy val env = Environment(environment)
    implicit lazy val mat: Materializer = ActorMaterializer()
    implicit lazy val ctx: ExecutionContextExecutor = system.dispatcher
    val app = AppDefinition(PathId("/app"), unreachableStrategy = UnreachableEnabled(1.seconds, 5.seconds))
    val app2 = AppDefinition(PathId("/app2"), unreachableStrategy = UnreachableEnabled())
    val pod = PodDefinition(PathId("/pod"), unreachableStrategy = UnreachableEnabled(1.seconds, 2.seconds))
    appRepository.all() returns Source(Seq(app, app2))
    appRepository.store(any) returns Future.successful(Done)
    podRepository.all() returns Source.single(pod)
    podRepository.store(any) returns Future.successful(Done)
  }

}
