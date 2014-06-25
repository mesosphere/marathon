package mesosphere.marathon.state

import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.BuildInfo
import scala.annotation.implicitNotFound

@implicitNotFound("Could not find Migration instance for ${A}")
trait Migration[A] {
  def needsMigration(version: StorageVersion): Boolean
  def migrate(version: StorageVersion, obj: A): A
}

object StorageVersions {
  val VersionRegex = """^(\d+)\.(\d+)\.(\d+).*""".r

  def apply(major: Int, minor: Int, patch: Int): StorageVersion = {
    StorageVersion
      .newBuilder()
      .setMajor(major)
      .setMinor(minor)
      .setPatch(patch)
      .build()
  }

  def current: StorageVersion = {
    BuildInfo.version match {
      case VersionRegex(major, minor, patch) =>
        StorageVersions(
          major.toInt,
          minor.toInt,
          patch.toInt
        )
    }
  }

  def empty: StorageVersion = StorageVersions(0, 0, 0)
}
