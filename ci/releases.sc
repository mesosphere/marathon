// Release targets and helper methods.

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import $file.awsClient
import $file.utils
import utils.SemVer

sealed trait ReleaseTarget {
  val name: String
}

// All release targets.
object ReleaseTarget {
  case object DockerTag extends ReleaseTarget {
    val name = "docker-tag"
  }
  case object DockerLatest extends ReleaseTarget {
    val name = "docker-latest"
  }
  case object LinuxPackages extends ReleaseTarget {
    val name = "linux-packages"
  }
  case object JARArtifact extends ReleaseTarget {
    val name = "jar-artifact"
  }
  case object S3Package extends ReleaseTarget {
    val name = "s3-package"
  }

  case object PluginInterface extends ReleaseTarget {
    val name = "plugin-interface"
  }

  val all = Seq(DockerTag, DockerLatest, S3Package, LinuxPackages, JARArtifact, PluginInterface)
}

implicit val SemVerRead: scopt.Read[SemVer] = scopt.Read.reads(SemVer(_))

/** Configration for a release
  *
 * @param version The version that will be released.
  * @param targets All release targets that should be executed.
  * @param runTests indicates whether to run tests of builds before a release.
  */
case class Config(version: SemVer = SemVer.empty, targets: List[ReleaseTarget] = Nil, runTests: Boolean = true)

// Scopt parser for release config
val parser = new scopt.OptionParser[Config]("scopt") {
  head("pipeline release")
  opt[SemVer]("version").required.action { (v, c) =>
    c.copy(version = v)
  }.text("build that should be released, e.g. 1.7.42-deadbeef")

  opt[Boolean]("run-tests").required.action { (runTests, c) =>
    c.copy(runTests = runTests)
  }.text("whether to run tests for build")
  arg[String]("targets...").unbounded.optional.action { (target, c) =>
    ReleaseTarget.all.find(_.name == target) match {
      case Some(rt) => c.copy(targets = rt :: c.targets)
      case None =>
        throw new RuntimeException(
          s"${target} is not a known release target; valid options are:\n* ${ReleaseTarget.all.map(_.name).mkString("\n* ")}"
        )
    }
  }.text(s"targets to release: ${ReleaseTarget.all.map(_.name).mkString(", ")}")
}

/**
  * Assert that version exists in builds.
  */
def verify(version: SemVer): Unit = {
  val build = awsClient.s3PathFor(s"builds/$version/marathon-$version.tgz")
  require(awsClient.doesS3FileExist(build), s"Build $version does not exist.")
}

/**
  * Copies tarball builds on S3 from `builds/<version>` to `releases/<version>.
  */
def copyTarballBuildsToReleases(version: SemVer): Unit = {
  val from = awsClient.s3PathFor(s"builds/$version")
  val to = awsClient.s3PathFor(s"releases/${version.toReleaseString}")

  println(s"Copy packages in ${from} to ${to}")
  awsClient.copy(from / s"marathon-$version.tgz", to / s"marathon-$version.tgz")
  awsClient.copy(from / s"marathon-$version.tgz.sha1", to / s"marathon-$version.tgz.sha1")
  awsClient.copy(from / s"marathon-docs-$version.tgz", to / s"marathon-docs-$version.tgz")
}

/**
  * Publish native Linx packages to package server.
  */
def uploadLinuxPackagesToRepos(packageVersion: String): Unit = {
  val toolPath = pwd / 'tools / 'packager
  %('make, "upload-packages")(toolPath)
}
