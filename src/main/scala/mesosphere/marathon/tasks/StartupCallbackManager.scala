package mesosphere.marathon.tasks

import scala.collection.concurrent.TrieMap
import java.util.concurrent.atomic.AtomicInteger
import org.apache.mesos.Protos.TaskState

class StartupCallbackManager {
  private [tasks] val callbacks = TrieMap[(String, TaskState), (AtomicInteger, Boolean => Unit)]()

  def add(appID: String, status: TaskState, count: Int)(f: Boolean => Unit): Unit =
    if (count <= 0) {
      f(true)
    } else {
      callbacks += (appID -> status) ->(new AtomicInteger(count), f)
    }

  def countdown(appID: String, status: TaskState): Unit =
    callbacks.get(appID -> status) foreach { case (i, f) =>
      if (i.decrementAndGet() == 0) {
        try {
          f(true)
        } finally {
          remove(appID, status)
        }
      }
    }

  def remove(appID: String, status: TaskState): Unit =
    callbacks.remove(appID -> status) foreach { case (i, f) =>
      if (i.get() > 0)
        f(false)
    }
}
