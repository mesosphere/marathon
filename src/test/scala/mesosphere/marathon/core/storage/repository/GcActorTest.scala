package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

import akka.testkit.{TestFSMRef, TestKitBase}
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.impl.GcActor
import mesosphere.marathon.core.storage.repository.impl.GcActor._
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.PathId
import mesosphere.marathon.test.Mockito

import scala.concurrent.{Future, blocking}

class GcActorTest extends AkkaUnitTest with TestKitBase with Mockito {
  implicit val metrics = new Metrics(new MetricRegistry)
  // scalastyle:off
  def waitOnSem(sem: Semaphore): Option[() => Future[ScanDone]] = {
    Some(() => Future {
      blocking(sem.acquire())
      ScanDone()
    })
  }

  private def processReceiveUntil[T <: GcActor[_, _, _]](fsm: TestFSMRef[State, _, T], state: State): State = {
    // give the blocking scan a little time to deliver the message
    var done = 0
    while (done < 5) {
      fsm.underlyingActor.receive
      Thread.sleep(5)
      if (fsm.stateName == state) done = 5
      else done += 1
    }
    fsm.stateName
  }

  case class Fixture(maxVersions: Int)(
    testScan: Option[() => Future[ScanDone]] = None)(
    testCompact: Option[(Set[PathId], Map[PathId, Set[OffsetDateTime]], Set[OffsetDateTime]) => Future[CompactDone]] = None) {
    val store = new InMemoryPersistenceStore()
    val appRepo = AppRepository.inMemRepository(store)
    val groupRepo = GroupRepository.inMemRepository(store, appRepo)
    val deployRepo = DeploymentRepository.inMemRepository(store, groupRepo, appRepo, maxVersions)
    val actor = TestFSMRef(new GcActor(deployRepo, groupRepo, appRepo, maxVersions) {
      override def scan(): Future[ScanDone] = {
        testScan.fold(super.scan())(_())
      }

      override def compact(appsToDelete: Set[PathId],
                           appVersionsToDelete: Map[PathId, Set[OffsetDateTime]],
                           rootVersionsToDelete: Set[OffsetDateTime]): Future[CompactDone] = {
        testCompact.fold(super.compact(appsToDelete, appVersionsToDelete, rootVersionsToDelete)) {
          _(appsToDelete, appVersionsToDelete, rootVersionsToDelete)
        }
      }
    })
  }
  // scalastyle:on

  "GcActor" should {
    // transitions
    "start idle" in {
      val f = Fixture(2)()()
      f.actor.stateName should equal(Idle)
    }
    "RunGC should move to Scanning" in {
      val scanCalled = new AtomicBoolean(false)

      val f = Fixture(2)(Some{ () =>
        scanCalled.set(true)
        Future.successful(ScanDone())
      })()

      f.actor ! RunGC
      f.actor.stateName should equal(Scanning)
      f.actor.stateData should equal(UpdatedEntities())
      scanCalled.get() should equal(true)
    }
    "ScanDone with no compactions and no additional requests should go back to idle" in {
      val f = Fixture(2)()()
      f.actor.setState(Scanning, UpdatedEntities())
      f.actor ! ScanDone()
      f.actor.stateName should equal(Idle)
    }
    "ScanDone with no compactions and additional requests should scan again" in {
      val scanSem = new Semaphore(0)
      val f = Fixture(2)(waitOnSem(scanSem))()

      f.actor.setState(Scanning, UpdatedEntities(gcRequested = true))
      f.actor ! ScanDone()
      f.actor.stateName should equal(Scanning)
      f.actor.stateData should equal(UpdatedEntities())
      scanSem.release()
      processReceiveUntil(f.actor, Idle) should be(Idle)
    }
    "CompactDone should transition to idle if no gcs were requested" in {
      val f = Fixture(2)()()
      f.actor.setState(Compacting, BlockedEntities())
      f.actor ! CompactDone
      f.actor.stateName should equal(Idle)
    }
    "CompactDone should transition to scanning if gcs were requested" in {
      val scanSem = new Semaphore(0)
      val f = Fixture(2)(waitOnSem(scanSem))()
      f.actor.setState(Compacting, BlockedEntities(gcRequested = true))
      f.actor ! CompactDone
      f.actor.stateName should equal(Scanning)
      f.actor.stateData should equal(UpdatedEntities())
      scanSem.release()
      processReceiveUntil(f.actor, Idle) should be(Idle)
    }
  }
}
