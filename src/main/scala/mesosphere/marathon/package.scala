package mesosphere

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
}
