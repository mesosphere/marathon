package mesosphere.marathon.core.readiness

import mesosphere.marathon.core.readiness.impl.DummyReadinessCheckExecutor

/**
  * Exposes everything necessary to execute readiness checks on tasks.
  *
  * A task might not be ready:
  *
  * * because it needs to run a migration (which potentially can be controlled using the DCOS Migration API)
  * * it needs to warm up its caches
  * * it needs to load some data
  * * ...
  */
class ReadinessModule {
  lazy val readinessCheckExecutor: ReadinessCheckExecutor = new DummyReadinessCheckExecutor()
}
