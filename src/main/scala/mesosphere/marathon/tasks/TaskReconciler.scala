package mesosphere.marathon.tasks
import mesosphere.marathon.state.PathId

/**
  * Query tasks and remove whole apps for reconciling task state.
  *
  * Actions taken to deal with missing/extra tasks are indirect:
  *
  * * tasks only in the tracker will be reported as LOST in the next reconciliation with Mesos
  * * tasks without tracker entry have to be killed and will be removed following the corresponding task update
  */
trait TaskReconciler extends TaskTracker {
  def removeUnknownAppAndItsTasks(appId: PathId): Unit
}
