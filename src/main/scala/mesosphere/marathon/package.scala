package mesosphere

import mesosphere.marathon.core.task.Task
import mesosphere.marathon.state.Timestamp

/**
  * Scala stupidly defines Seq/Indexed as "a generic sequence" which can be _mutable_
  *
  * Instead, provided you use
  * ```
  * package mesosphere.marathon
  * package subpackage
  * ```
  * the correct Seq type till be imported for you automatically.
  */
package object marathon {
  type Seq[+A] = scala.collection.immutable.Seq[A]
  val Seq = scala.collection.immutable.Seq

  type IndexedSeq[+A] = scala.collection.immutable.IndexedSeq[A]
  val IndexedSeq = scala.collection.immutable.IndexedSeq

  implicit class RichClock(val c: java.time.Clock) extends AnyVal {
    // This method was formerly implemented on the marathon Clock type to return Marathon's Timestamp type
    // We preserve it for now to reduce the size of the change to remove Marathon's Clock type.
    def now(): Timestamp = Timestamp.now(c)
  }

  object NonEmpty {
    def unapply[I <: Iterable[_]](iter: I): Boolean = iter.nonEmpty
  }

  /**
    * Task extractor from [[mesosphere.marathon.core.instance.Instance.tasksMap]].
    *
    * See [[mesosphere.marathon.core.appinfo.EnrichedTask.singleFromInstance()]] for an example.
    */
  object Tasks {
    def unapplySeq(iter: Map[Task.Id, Task]): Option[Seq[Task]] = {
      if (iter.nonEmpty) Some(iter.values.to[Seq])
      else None
    }
  }

  /**
    * This makes the silent compiler annotation available in our mesosphere.marathon prelude, and is used to suppress
    * compiler warnings.
    */
  type silent = com.github.ghik.silencer.silent
}
