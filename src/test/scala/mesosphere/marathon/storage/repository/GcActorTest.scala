package mesosphere.marathon.storage.repository

import java.time.OffsetDateTime
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.{ TestFSMRef, TestKitBase }
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.storage.store.impl.memory.{ Identity, InMemoryPersistenceStore, RamId }
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.VersionInfo
import mesosphere.marathon.state.{ AppDefinition, Group, PathId, Timestamp }
import mesosphere.marathon.test.Mockito
import mesosphere.marathon.upgrade.DeploymentPlan
import org.scalatest.GivenWhenThen

import scala.collection.immutable.Seq
import scala.concurrent.{ Future, Promise, blocking }

class GcActorTest extends AkkaUnitTest with TestKitBase with GivenWhenThen with Mockito {
  import GcActor._
  import PathId._
  implicit val metrics = new Metrics(new MetricRegistry)

  def scanWaitOnSem(sem: Semaphore): Option[() => Future[ScanDone]] = {
    Some(() => Future {
      blocking(sem.acquire())
      ScanDone()
    })
  }

  def compactWaitOnSem(
    appsToDelete: AtomicReference[Set[PathId]],
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
    while (done < 500) {
      Thread.`yield`()
      Thread.sleep(1)
      done = if (fsm.stateName == state) 500
      else done + 1
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

      override def compact(
        appsToDelete: Set[PathId],
        appVersionsToDelete: Map[PathId, Set[OffsetDateTime]],
        rootVersionsToDelete: Set[OffsetDateTime]): Future[CompactDone] = {
        testCompact.fold(super.compact(appsToDelete, appVersionsToDelete, rootVersionsToDelete)) {
          _(appsToDelete, appVersionsToDelete, rootVersionsToDelete)
        }
      }
    })
  }

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
        f.actor.stateName should equal(Scanning)
        f.actor.stateData should equal(UpdatedEntities())
        sem.release()
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
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), groups = Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), groups = Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), deployPromise)
        deployPromise.future.isCompleted should be(true)
        f.actor.stateData should equal(
          UpdatedEntities(
            appVersionsStored = Map(
              app1.id -> Set(app1.version.toOffsetDateTime),
              app2.id -> Set(app2.version.toOffsetDateTime)),
            rootsStored = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime)))
      }
      "remove stores from deletions when scan is done" in {
        val sem = new Semaphore(0)
        val compactedAppIds = new AtomicReference[Set[PathId]]()
        val compactedAppVersions = new AtomicReference[Map[PathId, Set[OffsetDateTime]]]()
        val compactedRoots = new AtomicReference[Set[OffsetDateTime]]()
        val f = Fixture(5)()(compactWaitOnSem(compactedAppIds, compactedAppVersions, compactedRoots, sem))
        f.actor.setState(Scanning, UpdatedEntities())
        val app1 = AppDefinition("a".toRootPath)
        val app2 = AppDefinition("b".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), groups = Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), groups = Set.empty, Set.empty)
        val updates = UpdatedEntities(
          appVersionsStored = Map(
            app1.id -> Set(app1.version.toOffsetDateTime),
            app2.id -> Set(app2.version.toOffsetDateTime)),
          rootsStored = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime))
        f.actor.setState(Scanning, updates)

        val now = OffsetDateTime.MAX
        f.actor ! ScanDone(
          appsToDelete = Set(app1.id, app2.id, "c".toRootPath),
          appVersionsToDelete = Map(
            app1.id -> Set(app1.version.toOffsetDateTime, now),
            app2.id -> Set(app2.version.toOffsetDateTime, now),
            "d".toRootPath -> Set(now)),
          rootVersionsToDelete = Set(root1.version.toOffsetDateTime, root2.version.toOffsetDateTime, now))

        f.actor.stateName should equal(Compacting)
        f.actor.stateData should equal(BlockedEntities(
          appsDeleting = Set("c".toRootPath),
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
        promise.future.futureValue should be(Done)
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
        f.actor.stateData should be(BlockedEntities(
          appVersionsDeleting = Map("a".toRootPath -> Set(now)),
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
        promise.future.futureValue should be(Done)
      }
      "let unblocked deploy stores through" in {
        val f = Fixture(2)()()
        f.actor.setState(Compacting, BlockedEntities())
        val promise = Promise[Done]()
        val app1 = AppDefinition("a".toRootPath)
        val app2 = AppDefinition("b".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), groups = Set.empty, Set.empty)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), groups = Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), promise)
        // internally we send two more messages as StorePlan in compacting is the same as StoreRoot x 2
        processReceiveUntil(f.actor, Compacting) should be(Compacting)
        promise.future.futureValue should be(Done)
        f.actor.stateData should be(BlockedEntities())
      }
      "block plans with deleted roots until compaction completes" in {
        val f = Fixture(2)()()
        val app1 = AppDefinition("a".toRootPath)
        val root1 = Group("/".toRootPath, Map("a".toRootPath -> app1), groups = Set.empty, Set.empty)

        f.actor.setState(Compacting, BlockedEntities(rootsDeleting = Set(root1.version.toOffsetDateTime)))
        val promise = Promise[Done]()
        val app2 = AppDefinition("b".toRootPath)
        val root2 = Group("/".toRootPath, Map("b".toRootPath -> app2), groups = Set.empty, Set.empty)
        f.actor ! StorePlan(DeploymentPlan(root1, root2, Nil, Timestamp.now()), promise)
        // internally we send two more messages as StorePlan in compacting is the same as StoreRoot x 2
        processReceiveUntil(f.actor, Compacting) should be(Compacting)
        promise.future.isCompleted should be(false)
        val stateData = f.actor.stateData.asInstanceOf[BlockedEntities]
        stateData.rootsDeleting should equal(Set(root1.version.toOffsetDateTime))
        stateData.promises should not be 'empty
        f.actor ! CompactDone
        processReceiveUntil(f.actor, Idle) should be(Idle)
        promise.future.futureValue should be(Done)
      }
    }
    "actually running" should {
      "ignore scan errors on roots" in {
        val store = new InMemoryPersistenceStore()
        val appRepo = AppRepository.inMemRepository(store)
        val groupRepo = mock[StoredGroupRepositoryImpl[RamId, String, Identity]]
        val deployRepo = DeploymentRepository.inMemRepository(store, groupRepo, appRepo, 1)
        val actor = TestFSMRef(new GcActor(deployRepo, groupRepo, appRepo, 1))
        groupRepo.rootVersions() returns Source(Seq(OffsetDateTime.now(), OffsetDateTime.MIN, OffsetDateTime.MAX))
        groupRepo.root() returns Future.failed(new Exception)
        actor ! RunGC
        processReceiveUntil(actor, Idle) should be(Idle)
      }
      "ignore scan errors on apps" in {
        val store = new InMemoryPersistenceStore()
        val appRepo = mock[AppRepositoryImpl[RamId, String, Identity]]
        val groupRepo = GroupRepository.inMemRepository(store, appRepo)
        val deployRepo = DeploymentRepository.inMemRepository(store, groupRepo, appRepo, 2)
        val actor = TestFSMRef(new GcActor(deployRepo, groupRepo, appRepo, 2))
        val root1 = Group("/".toRootPath)
        val root2 = Group("/".toRootPath)
        val root3 = Group("/".toRootPath)
        Seq(root1, root2, root3).foreach(groupRepo.storeRoot(_, Nil, Nil).futureValue)
        appRepo.ids returns Source.failed(new Exception)
        actor ! RunGC
        processReceiveUntil(actor, Idle) should be(Idle)
      }
      "ignore errors when compacting" in {
        val store = new InMemoryPersistenceStore()
        val appRepo = mock[AppRepositoryImpl[RamId, String, Identity]]
        val groupRepo = GroupRepository.inMemRepository(store, appRepo)
        val deployRepo = DeploymentRepository.inMemRepository(store, groupRepo, appRepo, 2)
        val actor = TestFSMRef(new GcActor(deployRepo, groupRepo, appRepo, 2))
        actor.setState(Scanning, UpdatedEntities())
        appRepo.delete(any) returns Future.failed(new Exception)
        actor ! ScanDone(appsToDelete = Set("a".toRootPath))
        processReceiveUntil(actor, Idle) should be(Idle)
      }
      "do nothing if there are less than max roots" in {
        val sem = new Semaphore(0)
        val compactedAppIds = new AtomicReference[Set[PathId]]()
        val compactedAppVersions = new AtomicReference[Map[PathId, Set[OffsetDateTime]]]()
        val compactedRoots = new AtomicReference[Set[OffsetDateTime]]()
        val f = Fixture(2)()(compactWaitOnSem(compactedAppIds, compactedAppVersions, compactedRoots, sem))
        val root1 = Group("/".toRootPath)
        val root2 = Group("/".toRootPath)
        Seq(root1, root2).foreach(f.groupRepo.storeRoot(_, Nil, Nil).futureValue)
        f.actor ! RunGC
        sem.release()
        processReceiveUntil(f.actor, Idle) should be(Idle)
        // compact shouldn't have been called.
        Option(compactedAppIds.get) should be('empty)
        Option(compactedAppVersions.get) should be('empty)
        Option(compactedRoots.get) should be('empty)
      }
      "do nothing if all of the roots are in use" in {
        val sem = new Semaphore(0)
        val compactedAppIds = new AtomicReference[Set[PathId]]()
        val compactedAppVersions = new AtomicReference[Map[PathId, Set[OffsetDateTime]]]()
        val compactedRoots = new AtomicReference[Set[OffsetDateTime]]()
        val f = Fixture(1)()(compactWaitOnSem(compactedAppIds, compactedAppVersions, compactedRoots, sem))
        val root1 = Group("/".toRootPath)
        val root2 = Group("/".toRootPath)
        Seq(root1, root2).foreach(f.groupRepo.storeRoot(_, Nil, Nil).futureValue)
        val plan = DeploymentPlan(root1, root2)
        f.deployRepo.store(plan).futureValue

        f.actor ! RunGC
        sem.release()
        processReceiveUntil(f.actor, Idle) should be(Idle)
        // compact shouldn't have been called.
        Option(compactedAppIds.get) should be('empty)
        Option(compactedAppVersions.get) should be('empty)
        Option(compactedRoots.get) should be('empty)
      }
      "delete unused apps and roots" in {
        val f = Fixture(1)()()
        val dApp1 = AppDefinition("a".toRootPath)
        val dApp2 = AppDefinition("b".toRootPath)
        val dApp1V2 = dApp1.copy(versionInfo = VersionInfo.OnlyVersion(Timestamp(7)))
        val app3 = AppDefinition("c".toRootPath)
        f.appRepo.store(dApp1).futureValue
        f.appRepo.storeVersion(dApp2).futureValue
        f.appRepo.store(app3)
        val dRoot1 = Group("/".toRootPath, Map(dApp1.id -> dApp1), version = Timestamp(1))
        f.groupRepo.storeRoot(dRoot1, dRoot1.transitiveApps.toVector, Seq(dApp2.id)).futureValue

        val root2 = Group("/".toRootPath, Map(app3.id -> app3, dApp1V2.id -> dApp1V2), version = Timestamp(2))
        val root3 = Group("/".toRootPath, version = Timestamp(3))
        val root4 = Group("/".toRootPath, Map(dApp1V2.id -> dApp1V2), version = Timestamp(4))
        f.groupRepo.storeRoot(root2, root2.transitiveApps.toVector, Nil).futureValue
        f.groupRepo.storeRoot(root3, Nil, Nil).futureValue

        val plan = DeploymentPlan(root2, root3)
        f.deployRepo.store(plan).futureValue
        f.groupRepo.storeRoot(root4, Nil, Nil).futureValue

        f.actor ! RunGC
        processReceiveUntil(f.actor, Idle) should be(Idle)
        // dApp1 -> delete only dApp1.version, dApp2 -> full delete, dRoot1 -> delete
        f.appRepo.ids().runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(dApp1.id, app3.id)
        f.appRepo.versions(dApp1.id).runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(dApp1V2.version.toOffsetDateTime)
        f.groupRepo.rootVersions().mapAsync(Int.MaxValue)(f.groupRepo.rootVersion).collect {
          case Some(g) => g
        }.runWith(Sink.seq).futureValue should
          contain theSameElementsAs Seq(root2, root3, root4)
      }
      "actually delete the requested objects" in {
        val appRepo = mock[AppRepositoryImpl[RamId, String, Identity]]
        val groupRepo = mock[StoredGroupRepositoryImpl[RamId, String, Identity]]
        val deployRepo = mock[DeploymentRepositoryImpl[RamId, String, Identity]]
        val actor = TestFSMRef(new GcActor(deployRepo, groupRepo, appRepo, 25))
        actor.setState(Scanning, UpdatedEntities())
        val scanResult = ScanDone(
          appsToDelete = Set("a".toRootPath),
          appVersionsToDelete = Map(
            "b".toRootPath -> Set(OffsetDateTime.MIN, OffsetDateTime.MAX),
            "c".toRootPath -> Set(OffsetDateTime.MIN)),
          rootVersionsToDelete = Set(OffsetDateTime.MIN, OffsetDateTime.MAX))

        appRepo.delete(any) returns Future.successful(Done)
        appRepo.deleteVersion(any, any) returns Future.successful(Done)
        groupRepo.deleteRootVersion(any) returns Future.successful(Done)

        actor ! scanResult

        processReceiveUntil(actor, Idle) should be(Idle)

        verify(appRepo).delete("a".toRootPath)
        verify(appRepo).deleteVersion("b".toRootPath, OffsetDateTime.MIN)
        verify(appRepo).deleteVersion("b".toRootPath, OffsetDateTime.MAX)
        verify(appRepo).deleteVersion("c".toRootPath, OffsetDateTime.MIN)
        verify(groupRepo).deleteRootVersion(OffsetDateTime.MIN)
        verify(groupRepo).deleteRootVersion(OffsetDateTime.MAX)
        noMoreInteractions(appRepo)
        noMoreInteractions(groupRepo)
        noMoreInteractions(deployRepo)
      }
    }
  }
}
