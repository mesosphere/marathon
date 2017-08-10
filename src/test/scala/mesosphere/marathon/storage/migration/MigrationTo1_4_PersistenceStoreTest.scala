package mesosphere.marathon.storage.migration

import java.util.UUID

import akka.stream.scaladsl.Sink
import com.codahale.metrics.MetricRegistry
import mesosphere.AkkaUnitTest
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.event.EventSubscribers
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.Status
import mesosphere.marathon.core.task.state.NetworkInfoPlaceholder
import mesosphere.marathon.core.task.tracker.impl.TaskSerializer
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository._
import mesosphere.marathon.storage.{ LegacyInMemConfig, LegacyStorageConfig }
import mesosphere.marathon.stream._
import mesosphere.marathon.test.{ GroupCreation, Mockito }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.state.FrameworkId
import org.apache.mesos

class MigrationTo1_4_PersistenceStoreTest extends AkkaUnitTest with Mockito with GroupCreation {
  val maxVersions = 25
  import mesosphere.marathon.state.PathId._

  def migration(legacyConfig: Option[LegacyStorageConfig] = None, maxVersions: Int = maxVersions): Migration = {
    implicit val metrics = new Metrics(new MetricRegistry)
    val persistenceStore = new InMemoryPersistenceStore()
    val appRepository = AppRepository.inMemRepository(persistenceStore)
    val podRepository = PodRepository.inMemRepository(persistenceStore)
    val groupRepository = GroupRepository.inMemRepository(persistenceStore, appRepository, podRepository)
    val deploymentRepository = DeploymentRepository.inMemRepository(persistenceStore, groupRepository, appRepository, podRepository, 25)
    val taskRepo = TaskRepository.inMemRepository(persistenceStore)
    val instanceRepo = InstanceRepository.inMemRepository(persistenceStore)
    val taskFailureRepository = TaskFailureRepository.inMemRepository(persistenceStore)
    val frameworkIdRepository = FrameworkIdRepository.inMemRepository(persistenceStore)
    val eventSubscribersRepository = EventSubscribersRepository.inMemRepository(persistenceStore)

    new Migration(Set.empty, legacyConfig, Some(persistenceStore), appRepository, podRepository, groupRepository, deploymentRepository,
      taskRepo, instanceRepo, taskFailureRepository, frameworkIdRepository, eventSubscribersRepository)
  }

  "Migration to PersistenceStore" when {
    "migrating framework id" should {
      "do nothing if it doesn't exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = FrameworkIdRepository.legacyRepository(config.entityStore[FrameworkId])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.frameworkIdRepo.get().futureValue should be('empty)
      }
      "migrate an existing value" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = FrameworkIdRepository.legacyRepository(config.entityStore[FrameworkId])
        val id = FrameworkId(UUID.randomUUID.toString)
        oldRepo.store(id).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.frameworkIdRepo.get().futureValue.value should equal(id)
        oldRepo.get().futureValue should be('empty)
      }
    }
    "migrating EventSubscribers" should {
      "do nothing if it doesn't exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = EventSubscribersRepository.legacyRepository(config.entityStore[EventSubscribers])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.eventSubscribersRepo.get().futureValue should be('empty)
      }
      "migrate an existing value" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = EventSubscribersRepository.legacyRepository(config.entityStore[EventSubscribers])
        val subscribers = EventSubscribers(Set(UUID.randomUUID().toString))
        oldRepo.store(subscribers).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.eventSubscribersRepo.get().futureValue.value should equal(subscribers)
        oldRepo.get().futureValue should be('empty)
      }
    }
    "migrating Tasks" should {
      "do nothing if no tasks exist" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.taskRepo.all().runWith(Sink.seq).futureValue should be('empty)
        migrator.instanceRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate all tasks" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskRepository.legacyRepository(config.entityStore[MarathonTaskState])
        val agentInfo = Instance.AgentInfo("abc", None, Nil)
        def setAgentInfo(builder: MarathonTask.Builder): MarathonTask = {
          builder.setOBSOLETEHost(agentInfo.host)
          agentInfo.agentId.foreach { agentId =>
            builder.setOBSOLETESlaveId(mesos.Protos.SlaveID.newBuilder().setValue(agentId))
          }
          builder.addAllOBSOLETEAttributes(agentInfo.attributes)
          builder.build()
        }
        val tasks = Seq(
          Task.LaunchedEphemeral(
            Task.Id.forRunSpec("123".toRootPath),
            Timestamp(0), Status(Timestamp(0), condition = Condition.Created, networkInfo = NetworkInfoPlaceholder())),
          Task.LaunchedEphemeral(
            Task.Id.forRunSpec("123".toRootPath),
            Timestamp(0), Status(Timestamp(0), condition = Condition.Created, networkInfo = NetworkInfoPlaceholder()))
        ).map { task =>
            val proto = TaskSerializer.toProto(task)
            // legacy tasks in store have agentInfo serialized
            setAgentInfo(proto.toBuilder)
          }
        tasks.foreach{ task =>
          oldRepo.storeRaw(task).futureValue
          oldRepo.getRaw(Task.Id(task.getId)).futureValue.value shouldEqual task
        }
        oldRepo.allRaw().runWith(Sink.seq).futureValue should have size 2

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        val expectedInstances = tasks.map(migrate.marathonTaskToInstance)

        val all = migrator.instanceRepo.all().runWith(Sink.seq).futureValue
        all should contain theSameElementsAs expectedInstances
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating TaskFailures" should {
      "do nothing if there are no failures" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskFailureRepository.legacyRepository(config.entityStore[TaskFailure])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.taskRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate the failures" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = TaskFailureRepository.legacyRepository(config.entityStore[TaskFailure])
        val failure1 = TaskFailure.empty.copy(appId = "123".toRootPath, timestamp = Timestamp(1))

        val failures = Seq(
          failure1,
          TaskFailure.empty.copy(appId = "234".toRootPath),
          failure1.copy(version = Timestamp(3))
        )
        failures.foreach(oldRepo.store(_).futureValue)

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        // we only keep 1 historical version, not 2
        migrator.taskFailureRepo.all().runWith(Sink.seq).futureValue should contain theSameElementsAs failures.tail
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating DeploymentPlans" should {
      "do nothing if there are no plans" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan])

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        migrator.deploymentRepository.all().runWith(Sink.seq).futureValue should be('empty)
      }
      "migrate the plans" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldRepo = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan])
        val appRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
        val podRepo = PodRepository.legacyRepository(config.entityStore[PodDefinition], maxVersions)
        val oldGroupRepo = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, appRepo, podRepo)

        val plans = Seq(
          DeploymentPlan(
            createRootGroup(version = Timestamp(1)),
            createRootGroup(version = Timestamp(2))),
          DeploymentPlan(
            createRootGroup(version = Timestamp(3)),
            createRootGroup(version = Timestamp(4))),
          DeploymentPlan(
            createRootGroup(version = Timestamp(1)),
            createRootGroup(version = Timestamp(2)))
        )
        plans.foreach { plan =>
          oldGroupRepo.storeRoot(plan.original, Nil, Nil, Nil, Nil).futureValue
          oldGroupRepo.storeRoot(plan.target, Nil, Nil, Nil, Nil).futureValue
          oldRepo.store(plan).futureValue
        }
        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        val migrated = migrator.deploymentRepository.all().runWith(Sink.seq).futureValue
        migrator.deploymentRepository.all().runWith(Sink.seq).futureValue should contain theSameElementsAs plans
        oldRepo.all().runWith(Sink.seq).futureValue should be('empty)
      }
    }
    "migrating Groups" should {
      "store an empty group if there are no groups" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val config = LegacyInMemConfig(maxVersions)
        val oldAppRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], maxVersions)
        val oldPodRepo = PodRepository.legacyRepository(config.entityStore[PodDefinition], maxVersions)
        val oldRepo = GroupRepository.legacyRepository(config.entityStore[Group], maxVersions, oldAppRepo, oldPodRepo)

        // intentionally storing an app, it should not be migrated and will be deleted.
        oldAppRepo.store(AppDefinition("deleted-app".toRootPath)).futureValue

        val migrator = migration(Some(config))
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        oldAppRepo.all().runWith(Sink.seq).futureValue should be('empty)

        migrator.appRepository.all().runWith(Sink.seq).futureValue should be('empty)
        migrator.appRepository.ids()
          .flatMapConcat(migrator.appRepository.versions)
          .runWith(Sink.seq).futureValue should be('empty)
        val emptyRoot = migrator.groupRepository.root().futureValue
        emptyRoot.transitiveAppsById should be('empty)
        emptyRoot.groupsById should be('empty)
        emptyRoot.id should be(StoredGroupRepositoryImpl.RootId)
        emptyRoot.dependencies should be('empty)
        migrator.groupRepository.rootVersions()
          .runWith(Sink.seq).futureValue should contain theSameElementsAs Seq(emptyRoot.version.toOffsetDateTime)
      }
      "store all the previous roots" in {
        implicit val metrics = new Metrics(new MetricRegistry)
        val oldMax = 3
        val config = LegacyInMemConfig(oldMax)
        val oldAppRepo = AppRepository.legacyRepository(config.entityStore[AppDefinition], oldMax)
        val oldPodRepo = PodRepository.legacyRepository(config.entityStore[PodDefinition], oldMax)
        val oldRepo = GroupRepository.legacyRepository(config.entityStore[Group], oldMax, oldAppRepo, oldPodRepo)

        // intentionally storing an app, it should not be migrated and will be deleted.
        oldAppRepo.store(AppDefinition("deleted-app".toRootPath)).futureValue

        val root1 = createRootGroup(version = Timestamp(1))
        val root2 = createRootGroup(apps = Map("abc".toRootPath -> AppDefinition("abc".toRootPath)), version = Timestamp(2))
        val root3 = createRootGroup(
          apps = Map("def".toRootPath -> AppDefinition("def".toRootPath)),
          groups = Set(createGroup("def".toRootPath, apps = Map("/def/abc".toRootPath -> AppDefinition("/def/abc".toRootPath)))),
          version = Timestamp(3))

        oldRepo.storeRoot(root1, Nil, Nil, Nil, Nil).futureValue
        oldRepo.storeRoot(root2, root2.transitiveApps.toIndexedSeq, Nil, Nil, Nil).futureValue
        oldRepo.storeRoot(root3, root3.transitiveApps.toIndexedSeq, root2.transitiveAppIds.toIndexedSeq, Nil, Nil).futureValue

        val roots = Seq(root1, root2, root3)

        // one less root version than the old, but doesn't matter because it doesn't run GC.
        val migrator = migration(Some(config), 2)
        val migrate = new MigrationTo1_4_PersistenceStore(migrator)
        migrate.migrate().futureValue

        oldAppRepo.all().runWith(Sink.seq).futureValue should be('empty)
        oldRepo.rootVersions().runWith(Sink.seq).futureValue should be('empty)

        migrator.groupRepository.root().futureValue should equal(root3)
        migrator.groupRepository.rootVersions().mapAsync(Int.MaxValue)(migrator.groupRepository.rootVersion)
          .collect { case Some(g) => g }
          .runWith(Sink.seq).futureValue should contain theSameElementsAs roots

        // we don't need to verify app repository as the new persistence store doesn't
        // store the apps in the groups, so if the roots load, we're all good.
        val appIds = migrator.appRepository.ids().runWith(Sink.seq).futureValue
        appIds should not contain "deleted-app".toRootPath
        appIds should not be 'empty
      }
    }
  }
}

