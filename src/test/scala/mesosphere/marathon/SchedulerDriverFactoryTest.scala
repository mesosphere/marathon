package mesosphere.marathon

import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.core.instance.{Instance, TestInstanceBuilder}
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.storage.repository.{FrameworkIdRepository, InstanceRepository}
import mesosphere.marathon.test.TestCrashStrategy

import org.scalatest.Inside
import scala.util.{Try, Failure}

class SchedulerDriverFactoryTest extends AkkaUnitTest with Inside {
  val metrics = DummyMetrics
  val appId = "/test/foo/bla/rest".toPath
  val instanceId = Instance.Id.forRunSpec(appId)

  class Fixture {
    val store = new InMemoryPersistenceStore(metrics)
    val zkTimeout = implicitly[PatienceConfig].timeout
    val frameworkIdRepository = FrameworkIdRepository.inMemRepository(store)
    val instanceRepository = InstanceRepository.inMemRepository(store)
    val crashStrategy = new TestCrashStrategy()
    store.markOpen()
  }

  "getFrameworkId throws an exception and crashes if frameworkId is undefined but there are instances defined" in new Fixture {
    instanceRepository.store(state.Instance.fromCoreInstance(TestInstanceBuilder.emptyInstance(instanceId = instanceId))).futureValue

    inside(Try(
      MesosSchedulerDriverFactory.getFrameworkId(
        crashStrategy,
        zkTimeout,
        frameworkIdRepository,
        instanceRepository))) {
      case Failure(ex: RuntimeException) =>
        ex.getMessage.should(include("Refusing"))
    }

    crashStrategy.crashedReason shouldBe Some(CrashStrategy.FrameworkIdMissing)
  }

  "getFrameworkId returns None if frameworkId is undefined and instance repository is empty" in new Fixture {
    MesosSchedulerDriverFactory.getFrameworkId(
      crashStrategy,
      zkTimeout,
      frameworkIdRepository,
      instanceRepository) shouldBe None
  }
}
