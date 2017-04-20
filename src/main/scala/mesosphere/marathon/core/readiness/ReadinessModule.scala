package mesosphere.marathon
package core.readiness

import akka.actor.ActorSystem
import akka.stream.Materializer
import mesosphere.marathon.core.readiness.impl.ReadinessCheckExecutorImpl

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
class ReadinessModule(actorSystem: ActorSystem, materializer: Materializer) {
  lazy val readinessCheckExecutor: ReadinessCheckExecutor = new ReadinessCheckExecutorImpl()(actorSystem, materializer)
}
