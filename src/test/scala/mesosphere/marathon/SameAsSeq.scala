package mesosphere.marathon

import org.hamcrest.{ Description, BaseMatcher }
import org.mockito.internal.matchers.Equality

// WTF, this is not true:
// require(Map.empty[String,MarathonTask].values == Map.empty[String,MarathonTask].values)
case class SameAsSeq[T](wanted: Iterable[T]) extends BaseMatcher[Iterable[T]] {
  override def matches(actual: Any): Boolean = {
    Equality.areEqual(wanted.toSeq, actual.asInstanceOf[Iterable[T]].toSeq)
  }

  override def describeTo(description: Description): Unit = {
    description.appendValue(wanted)
  }
}

