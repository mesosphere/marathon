package mesosphere.marathon
package functional

import java.util.function._

import scala.compat.java8.FunctionConverters._
import scala.language.implicitConversions

trait FunctionConversions {
  @inline implicit def asBiConsumer[A, B, R](f: (A, B) => R): BiConsumer[A, B] =
    asJavaBiConsumer { (a: A, b: B) =>
      f(a, b)
      ()
    }
  @inline implicit def asBiFunction[A, B, R](f: (A, B) => R): BiFunction[A, B, R] = asJavaBiFunction[A, B, R](f)
  @inline implicit def asBinaryOperator[A](f: (A, A) => A): BinaryOperator[A] = asJavaBinaryOperator[A](f)
  @inline implicit def asBiPredicate[A, B](f: (A, B) => Boolean): BiPredicate[A, B] = asJavaBiPredicate[A, B](f)
  @inline implicit def asBooleanSupplier(f: () => Boolean): BooleanSupplier = asJavaBooleanSupplier(f)
  @inline implicit def asConsumer[A, R](f: A => R): Consumer[A] =
    asJavaConsumer { (a: A) =>
      f(a)
      ()
    }
  @inline implicit def asDoubleBinaryOperator(f: (Double, Double) => Double): DoubleBinaryOperator = asJavaDoubleBinaryOperator(f)
  @inline implicit def asDoubleConsumer[R](f: Double => R): DoubleConsumer =
    asJavaDoubleConsumer { (a: Double) =>
      f(a)
      ()
    }
  @inline implicit def asDoubleFunction[R](f: Double => R): DoubleFunction[R] = asJavaDoubleFunction[R](f)
  @inline implicit def asDoubleSupplier(f: () => Double): DoubleSupplier = asJavaDoubleSupplier(f)
  @inline implicit def asDoublePredicte(f: Double => Boolean): DoublePredicate = asJavaDoublePredicate(f)
  @inline implicit def asDoubleToIntFunction(f: Double => Int): DoubleToIntFunction = asJavaDoubleToIntFunction(f)
  @inline implicit def asDoubleToLongFunction(f: Double => Long): DoubleToLongFunction = asJavaDoubleToLongFunction(f)
  @inline implicit def asDoubleUnaryOperator(f: Double => Double): DoubleUnaryOperator = asJavaDoubleUnaryOperator(f)
  @inline implicit def asFunction[A, R](f: A => R): Function[A, R] = asJavaFunction(f)
  @inline implicit def asIntBinaryOperator(f: (Int, Int) => Int): IntBinaryOperator = asJavaIntBinaryOperator(f)
  @inline implicit def asIntConsumer[R](f: Int => R): IntConsumer =
    asJavaIntConsumer { (a: Int) =>
      f(a)
      ()
    }
  @inline implicit def asIntFunction[R](f: Int => R): IntFunction[R] = asJavaIntFunction[R](f)
  @inline implicit def asIntPredicate(f: Int => Boolean): IntPredicate = asJavaIntPredicate(f)
  @inline implicit def asIntSupplier(f: () => Int): IntSupplier = asJavaIntSupplier(f)
  @inline implicit def asIntToDoubleFunction(f: Int => Double): IntToDoubleFunction = asJavaIntToDoubleFunction(f)
  @inline implicit def asIntToLongFunction(f: Int => Long): IntToLongFunction = asJavaIntToLongFunction(f)
  @inline implicit def asIntUnaryOperator(f: Int => Int): IntUnaryOperator = asJavaIntUnaryOperator(f)
  @inline implicit def asLongBinaryOperator(f: (Long, Long) => Long): LongBinaryOperator = asJavaLongBinaryOperator(f)
  @inline implicit def asLongConsumer[R](f: Long => R): LongConsumer =
    asJavaLongConsumer { (a: Long) =>
      f(a)
      ()
    }
  @inline implicit def asLongFunction[R](f: Long => R): LongFunction[R] = asJavaLongFunction[R](f)
  @inline implicit def asLongPredicate(f: Long => Boolean): LongPredicate = asJavaLongPredicate(f)
  @inline implicit def asLongSupplier(f: () => Long): LongSupplier = asJavaLongSupplier(f)
  @inline implicit def asLongToDoubleFunction(f: Long => Double): LongToDoubleFunction = asJavaLongToDoubleFunction(f)
  @inline implicit def asLongToIntFunction(f: Long => Int): LongToIntFunction = asJavaLongToIntFunction(f)
  @inline implicit def asLongUnaryOperator(f: Long => Long): LongUnaryOperator = asJavaLongUnaryOperator(f)
  @inline implicit def asObjDoubleConsumer[A, R](f: (A, Double) => R): ObjDoubleConsumer[A] =
    asObjDoubleConsumer { (a: A, b: Double) =>
      f(a, b)
      ()
    }
  @inline implicit def asObjIntConsumer[A, R](f: (A, Int) => R): ObjIntConsumer[A] =
    asObjIntConsumer { (a: A, b: Int) =>
      f(a, b)
      ()
    }
  @inline implicit def asObjLongConsumer[A, R](f: (A, Long) => R): ObjLongConsumer[A] =
    asObjLongConsumer { (a: A, b: Long) =>
      f(a, b)
      ()
    }
  @inline implicit def asPredicate[A](f: A => Boolean): Predicate[A] = asJavaPredicate[A](f)
  @inline implicit def asSupplier[R](f: () => R): Supplier[R] = asJavaSupplier[R](f)
  @inline implicit def asToDoubleBiFunction[A, B](f: (A, B) => Double): ToDoubleBiFunction[A, B] = asJavaToDoubleBiFunction[A, B](f)
  @inline implicit def asToDoubleFunction[A](f: A => Double): ToDoubleFunction[A] = asJavaToDoubleFunction[A](f)
  @inline implicit def asToIntBiFunction[A, B](f: (A, B) => Int): ToIntBiFunction[A, B] = asJavaToIntBiFunction[A, B](f)
  @inline implicit def asToIntFunction[A](f: A => Int): ToIntFunction[A] = asJavaToIntFunction[A](f)
  @inline implicit def asLongBiFunction[A, B](f: (A, B) => Long): ToLongBiFunction[A, B] = asJavaToLongBiFunction[A, B](f)
  @inline implicit def asLongFunction[A](f: A => Long): ToLongFunction[A] = asJavaToLongFunction[A](f)
  @inline implicit def asUnaryOperator[A](f: A => A): UnaryOperator[A] = asJavaUnaryOperator[A](f)
}

object FunctionConversions extends FunctionConversions
