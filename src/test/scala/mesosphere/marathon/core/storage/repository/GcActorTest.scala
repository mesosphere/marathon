package mesosphere.marathon.core.storage.repository

import java.time.OffsetDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.testkit.{TestFSMRef, TestKitBase}
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.repository.impl.GcActor._
import mesosphere.marathon.core.storage.repository.impl.{GcActor, StoredGroup}
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{AppDefinition, Group, PathId, Timestamp}
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.GivenWhenThen

import scala.concurrent.{Future, Promise, blocking}

class GcActorTest extends AkkaUnitTest with TestKitBase with GivenWhenThen {
  import PathId._
  implicit val metrics = new Metrics(new MetricRegistry)
  // scalastyle:off
  def scanWaitOnSem(sem: Semaphore): Option[() => Future[ScanDone]] = {
    Some(() => Future {
      blocking(sem.acquire())
      ScanDone()
    })
  }

  def compactWaitOnSem(appsToDelete: AtomicReference[Set[PathId]],
                       appVersionsToDelete: AtomicReference[Map[PathId, Set[OffsetDateTime]]],
                       rootVersionsToDelete: AtomicReference[Set[OffsetDateTime]],
                       sem: Semaphore): Option[(Set[PathId], Map[PathId, Set[OffsetDateTime]], Set[OffsetDateTime]) => Future[CompactDone]] = {
    Some((apps, appVersions, roots) => Future {
      appsToDelete.set(apps)
      appVersionsToDelete.set(appVersions)
      rootVersionsToDelete.set(roots)
      blocking(sem.acquire())
      CompactDone
    })
  }



  private def processReceiveUntil[T <: GcActor[_, _, _]](fsm: TestFSMRef[State, _, T], state: State): State = {
    // give the blocking scan a little time to deliver the message
    var done = 0
    while (done < 50) {
      Thread.sleep(1)
      if (fsm.stateName == state) done = 50
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

  "GcActor" when {
    "transitioning" should {
      "start idle" in {
        val f = Fixture(2)()()
        f.actor.stateName should equal(Idle)
      }
      "RunGC should move to Scanning" in {
        val sem = new Semaphore(0)

        val f = Fixture(2)(scanWaitOnSem(sem))()

        f.actor ! RunGC
        sem.release()
        processReceiveUntil(f.actor, Scanning) should equal(Scanning)
        f.actor.stateData should equal(UpdatedEntities())
      }
      "RunGC while scanning should set 'scan again'" in {
        val f = Fixture(2)()()
        f.actor.setState(Scanning, UpdatedEntities())
        f.actor ! RunGC
        f.actor.stateName should equal(Scanning)
        f.actor.stateData should equal(UpdatedEntities(gcRequested = true))
      }
      "RunGC while compacting should set 'scan again'" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        f.actor ! RunGC
        f.actor.stateName should equal(Compacting)
        f.actor.stateData should equal(BlockedEntities(gcRequested = true))
      }
      "ScanDone with no compactions and no additional requests should go back to idle" in {
        val f = Fixture(2)()()
        f.actor.setState(Scanning, UpdatedEntities())
        f.actor ! ScanDone()
        f.actor.stateName should equal(Idle)
      }
      "ScanDone with no compactions and additional requests should scan again" in {
        val scanSem = new Semaphore(0)
        val f = Fixture(2)(scanWaitOnSem(scanSem))()

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
        val f = Fixture(2)(scanWaitOnSem(scanSem))()
        f.actor.setState(Compacting, BlockedEntities(gcRequested = true))
        f.actor ! CompactDone
        f.actor.stateName should equal(Scanning)
        f.actor.stateData should equal(UpdatedEntities())
        scanSem.release()
        processReceiveUntil(f.actor, Idle) should be(Idle)
      }
    }
    "idle" should {
      "complete stores immediately and stay idle" in {
        val f = Fixture(2)()()
        val appPromise = Promise[Done]()
        f.actor ! StoreApp("root".toRootPath, None, appPromise)
        appPromise.future.isCompleted should equal(true)
        f.actor.stateName should be(Idle)
      }
    }
    "scanning" should {
      "track app stores" in {
        val f = Fixture(2)()()
        f.actor.setState(Scanning, UpdatedEntities())
        val appPromise = Promise[Done]()
        f.actor ! StoreApp("root".toRootPath, None, appPromise)
        appPromise.future.isCompleted should be(true)
        f.actor.stateData should equal(UpdatedEntities(appsStored = Set("root".toRootPath)))
      }
      "track app version stores" in {
        val f = Fixture(2)()()
        f.actor.setState(Scanning, UpdatedEntities())
        val appPromise = Promise[Done]()
        val now = OffsetDateTime.now()
        f.actor ! StoreApp("root".toRootPath, Some(now), appPromise)
        appPromise.future.isCompleted should be(true)
        f.actor.stateData should equal(UpdatedEntities(appVersionsStored = Map("root".toRootPath -> Set(now))))
      }
      "track root stores" in {
        val f = Fixture(2)()()
        f.actor.setState(Scanning, UpdatedEntities())
        val rootPromise = Promise[Done]()
        val now = OffsetDateTime.now()
        val root = StoredGroup("/".toRootPath, Map("a".toRootPath -> now), Nil, Set.empty, now)
        f.actor ! StoreRoot(root, rootPromise)
        rootPromise.future.isCompleted should be(true)
        f.actor.stateData should equal(UpdatedEntities(appVersionsStored = root.appIds.mapValues(Set(_)), rootsStored = Set(now)))
      }
      "track deploy stores" in {
        val f = Fixture(5)()()
        f.actor.setState(Scanning, UpdatedEntities())
        val deployPromise = Promise[Done]()
        val app1 = AppDefinition("a".toRootPath)
        val app2 = AppDefinition("b".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), deployPromise)
        deployPromise.future.isCompleted should be(true)
        f.actor.stateData should equal(
          UpdatedEntities(appVersionsStored = Map(app1.id -> Set(app1.version.toOffsetDateTime),
          app2.id -> Set(app2.version.toOffsetDateTime)),
            rootsStored = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime)))
      }
      "remove stores when scan is done" in {
        val sem = new Semaphore(0)
        val compactedAppIds = new AtomicReference[Set[PathId]]()
        val compactedAppVersions = new AtomicReference[Map[PathId, Set[OffsetDateTime]]]()
        val compactedRoots = new AtomicReference[Set[OffsetDateTime]]()
        val f = Fixture(5)()(compactWaitOnSem(compactedAppIds, compactedAppVersions, compactedRoots, sem))
        f.actor.setState(Scanning, UpdatedEntities())
        val app1 = AppDefinition("a".toRootPath)
        val app2 = AppDefinition("b".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), Set.empty, Set.empty)
        val updates = UpdatedEntities(appVersionsStored = Map(app1.id -> Set(app1.version.toOffsetDateTime),
          app2.id -> Set(app2.version.toOffsetDateTime)),
          rootsStored = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime))
        f.actor.setState(Scanning, updates)

        val now = OffsetDateTime.now
        f.actor ! ScanDone(appsToDelete = Set(app1.id, app2.id, "c".toRootPath),
          appVersionsToDelete = Map(app1.id -> Set(app1.version.toOffsetDateTime, now),
            app2.id -> Set(app2.version.toOffsetDateTime, now),
            "d".toRootPath -> Set(now)),
          rootVersionsToDelete = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime, now))

        f.actor.stateName should equal(Compacting)
        f.actor.stateData should equal(BlockedEntities(appsDeleting = Set("c".toRootPath),
          appVersionsDeleting = Map(app1.id -> Set(now), app2.id -> Set(now), "d".toRootPath -> Set(now)),
          rootsDeleting = Set(now)))

        sem.release()
        processReceiveUntil(f.actor, Idle) should be(Idle)
        compactedAppIds.get should equal(Set("c".toRootPath))
        compactedAppVersions.get should equal(Map(app1.id -> Set(now), app2.id -> Set(now), "d".toRootPath -> Set(now)))
        compactedRoots.get should equal(Set(now))
      }
    }
    "compacting" should {
      "let unblocked app stores through" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        val promise = Promise[Done]()
        f.actor ! StoreApp("a".toRootPath, None, promise)
        promise.future.isCompleted should be(true)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities())
      }
      "block deleted app stores until compaction completes" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities(appsDeleting = Set("a".toRootPath)))
        val promise = Promise[Done]()
        f.actor ! StoreApp("a".toRootPath, None, promise)
        promise.future.isCompleted should be(false)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities(appsDeleting = Set("a".toRootPath), promises = List(promise)))
        f.actor ! CompactDone
        promise.future.isCompleted should be(true)
      }
      "let unblocked app version stores through" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        val promise = Promise[Done]()
        f.actor ! StoreApp("a".toRootPath, Some(OffsetDateTime.now), promise)
        promise.future.isCompleted should be(true)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities())
      }
      "block deleted app version stores until compaction completes" in {
        val f = Fixture(2)()()
        val now = OffsetDateTime.now()
        f.actor.setState(Compacting, BlockedEntities(appVersionsDeleting = Map("a".toRootPath -> Set(now))))
        val promise = Promise[Done]()
        f.actor ! StoreApp("a".toRootPath, Some(now), promise)
        promise.future.isCompleted should be(false)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities(appVersionsDeleting = Map("a".toRootPath -> Set(now)),
          promises = List(promise)))
        f.actor ! CompactDone
        promise.future.isCompleted should be(true)
      }
      "let unblocked root stores through" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        val promise = Promise[Done]()
        f.actor ! StoreRoot(StoredGroup("/".toRootPath, Map.empty, Nil, Set.empty, OffsetDateTime.now), promise)
        promise.future.isCompleted should be(true)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities())
      }
      "block deleted root stores until compaction completes" in {
        val f = Fixture(2)()()
        val now = OffsetDateTime.now
        f.actor.setState(Compacting, BlockedEntities(rootsDeleting = Set(now)))
        val promise = Promise[Done]()
        f.actor ! StoreRoot(StoredGroup("/".toRootPath, Map.empty, Nil, Set.empty, now), promise)
        promise.future.isCompleted should be(false)
        f.actor.stateName should be(Compacting)
        f.actor.stateData should be(BlockedEntities(rootsDeleting = Set(now), promises = List(promise)))
        f.actor ! CompactDone
        promise.future.isCompleted should be(true)
      }
      "let unblocked deploy stores through" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        val promise = Promise[Done]()
        val app1 = AppDefinition("a".toRootPath)
        val app2 = AppDefinition("b".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), promise)
        // internally we send two more messages as StorePlan in compacting is the same as StoreRoot x 2
        processReceiveUntil(f.actor, Compacting) should be(Compacting)
        promise.future.isCompleted should be(true)
        f.actor.stateData should be(BlockedEntities())
      }
      "block plans with deleted roots until compaction completes" in {
        val f = Fixture(2)()()
        val app1 = AppDefinition("a".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), Set.empty, Set.empty)

        f.actor.setState(Compacting, BlockedEntities(rootsDeleting = Set(root1.version.toOffsetDateTime)))
        val promise = Promise[Done]()
        val app2 = AppDefinition("b".toRootPath)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), promise)
        // internally we send two more messages as StorePlan in compacting is the same as StoreRoot x 2
        processReceiveUntil(f.actor, Compacting) should be(Compacting)
        promise.future.isCompleted should be(false)
        val stateData = f.actor.stateData.asInstanceOf[BlockedEntities]
        stateData.rootsDeleting should equal(Set(root1.version.toOffsetDateTime))
        stateData.promises should not be 'empty
        f.actor ! CompactDone
        processReceiveUntil(f.actor, Idle) should be(Idle)
        promise.future.isCompleted should be(true)
      }
    }
  }
}
