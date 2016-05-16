package mesosphere.util

class CircularBufferIterator[T](buffer: Array[T], start: Int) extends Iterator[T] {
  var idx = 0

  override def hasNext: Boolean = idx < buffer.size

  override def next: T = {
    val i = idx
    idx = idx + 1
    buffer(i)
  }
}

class CircularBuffer[T](size: Int, var isFull: Boolean = false)(implicit m: Manifest[T]) extends Seq[T] {
  val buffer = new Array[T](size)
  var bIdx = 0

  override def apply(idx: Int): T = buffer((bIdx + idx + size) % size)

  override def length: Int = size

  override def iterator: Iterator[T] = new CircularBufferIterator[T](buffer, bIdx)

  def add(e: T): Unit = {
    buffer(bIdx) = e
    if (!isFull && bIdx + 1 == size) isFull = true
    bIdx = (bIdx + 1) % size
  }

  def nextValue: T = apply(0)
}
