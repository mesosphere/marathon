package mesosphere.marathon.util

import scala.language.implicitConversions
import java.{ time, util }
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ Config, ConfigMemorySize }

import scala.collection.JavaConversions._
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration

/**
  * Extensions to [[com.typesafe.config.Config]] to support scala types and optionals.
  */
// scalastyle:off
class RichConfig(val config: Config) extends AnyVal {
  private def optional[T](path: String, ifSet: Config => T): Option[T] = {
    if (config.hasPath(path)) {
      Some(ifSet(config))
    } else {
      Option.empty[T]
    }
  }
  private def list[A, B](path: String, nonEmpty: Config => util.List[A],
    ifEmpty: Seq[B])(implicit toScala: A => B): Seq[B] = {
    if (config.hasPath(path)) {
      nonEmpty(config).to[Seq].map(toScala)
    } else {
      ifEmpty
    }
  }

  private implicit def toFiniteDuration(jd: time.Duration): Duration = {
    if (jd == time.Duration.ZERO) {
      Duration.Zero
    } else {
      Duration(jd.toNanos, TimeUnit.NANOSECONDS)
    }
  }

  def bool(path: String): Boolean = config.getBoolean(path)
  def bool(path: String, default: Boolean): Boolean = optionalBool(path).getOrElse(default)
  def optionalBool(path: String): Option[Boolean] = optional(path, _.getBoolean(path))
  def boolList(path: String, ifEmpty: Seq[Boolean] = Nil): Seq[Boolean] = list(path, _.getBooleanList(path), ifEmpty)

  def bytes(path: String): Long = config.getBytes(path)
  def bytes(path: String, default: Long): Long = optionalBytes(path).getOrElse(default)
  def optionalBytes(path: String): Option[Long] = optional(path, _.getBytes(path))
  def bytesList(path: String, ifEmpty: Seq[Long] = Nil): Seq[Long] = list(path, _.getBytesList(path), ifEmpty)

  def config(path: String): Config = config.getConfig(path)
  def optionalConfig(path: String) = optional(path, _.getConfig(path))

  def double(path: String): Double = config.getDouble(path)
  def double(path: String, default: Double): Double = optionalDouble(path).getOrElse(default)
  def optionalDouble(path: String): Option[Double] = optional(path, _.getDouble(path))
  def doubleList(path: String, ifEmpty: Seq[Double] = Nil): Seq[Double] = list(path, _.getDoubleList(path), ifEmpty)

  def duration(path: String): Duration = config.getDuration(path)
  def duration(path: String, default: Duration): Duration = optionalDuration(path).getOrElse(default)
  def optionalDuration(path: String): Option[Duration] = optional(path, _.getDuration(path))
  def durationList(path: String, ifEmpty: Seq[Duration] = Nil): Seq[Duration] =
    list(path, _.getDurationList(path), ifEmpty)

  def int(path: String): Int = config.getInt(path)
  def int(path: String, default: Int): Int = optionalInt(path).getOrElse(default)
  def optionalInt(path: String): Option[Int] = optional(path, _.getInt(path))
  def intList(path: String, ifEmpty: Seq[Int] = Nil): Seq[Int] = list(path, _.getIntList(path), ifEmpty)

  def long(path: String): Long = config.getLong(path)
  def long(path: String, default: Long): Long = optionalLong(path).getOrElse(default)
  def optionalLong(path: String): Option[Long] = optional(path, _.getLong(path))
  def longList(path: String, ifEmpty: Seq[Long] = Nil): Seq[Long] = list(path, _.getLongList(path), ifEmpty)

  def memorySize(path: String): ConfigMemorySize = config.getMemorySize(path)
  def memorySize(path: String, default: ConfigMemorySize): ConfigMemorySize =
    optionalMemorySize(path).getOrElse(default)
  def optionalMemorySize(path: String): Option[ConfigMemorySize] = optional(path, _.getMemorySize(path))
  def memorySizeList(path: String, ifEmpty: Seq[ConfigMemorySize] = Nil): Seq[ConfigMemorySize] =
    list(path, _.getMemorySizeList(path), ifEmpty)

  def number(path: String): Number = config.getNumber(path)
  def number(path: String, default: Number): Number = optionalNumber(path).getOrElse(default)
  def optionalNumber(path: String): Option[Number] = optional(path, _.getNumber(path))
  def numberList(path: String, ifEmpty: Seq[Number] = Nil): Seq[Number] = list(path, _.getNumberList(path), ifEmpty)

  def string(path: String): String = config.getString(path)
  def string(path: String, default: String): String = optionalString(path).getOrElse(default)
  def optionalString(path: String): Option[String] = optional(path, _.getString(path))
  def stringList(path: String, ifEmpty: Seq[String] = Nil): Seq[String] = list(path, _.getStringList(path), ifEmpty)
}
