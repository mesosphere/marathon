#!/usr/bin/env amm

import $ivy.`com.typesafe.play::play-json:2.6.0`
import $ivy.`org.eclipse.jgit:org.eclipse.jgit:4.8.0.201706111038-r`

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.{ RefSpec, URIish }
import play.api.libs.json.Json

// BuildInfo Models and their Json formats.
case class SingleSource(kind:String, url: String, sha1: String)
case class BuildInfo(
  requires: List[String],
  single_source: SingleSource,
  username: String,
  state_directory: Boolean
)
implicit val singleSourceFormat = Json.format[SingleSource]
implicit val buildInfoFormat = Json.format[BuildInfo]

// Configuration
val dcosRepoPath = pwd / 'dcos

/**
 * Clones repository from uri repo and checksout branch. The local repository
 * is removed once f finished.
 *
 * @param repo Repository address.
 * @param branch Branch that is checkout out.
 * @param f The function that is applied to the repository.
 */
def withRepository(repo: String, branch: String)(f: Git => Unit): Unit = {
  // Make sure the path is empty for cloning.
  rm! dcosRepoPath

  // Clone
  val git = Git.cloneRepository()
    .setURI(repo)
    .setBranch(branch)
    .setDirectory(dcosRepoPath.toIO)
    .call()

  try {
    f(git)
  } finally {
    // Leave a clean work directory.
    rm! dcosRepoPath
  }
}

/**
 * Pulls latest changes from dcos/dcos repository.
 *
 * @param git The Git repository that will be updated.
 */
def upgradeDCOS(git: Git): Unit = {
  // Update with latest DC/OS
  val remoteAddCmd = git.remoteAdd()
  remoteAddCmd.setName("dcos")
  remoteAddCmd.setUri(new URIish("https://github.com/dcos/dcos.git"))
  remoteAddCmd.call()

  git.pull()
    .setRemote("dcos")
    .setRemoteBranchName("master")
    .setStrategy(MergeStrategy.THEIRS)
    .call()
}

/**
 * Loads and changes the buildinfo in the Marathon packages.
 *
 * @param url The URL to the new Marathon artifact.
 * @param sha1 The sha1 checksum of the Marathon artifact.
 */
@main
def updateBuildInfo(url: String, sha1: String): Unit = {
  // Load
  val buildInfoPath = dcosRepoPath / 'packages/ 'marathon / "buildinfo.json"
  val buildInfoData = read(buildInfoPath)

  val buildInfo = Json.parse(buildInfoData).as[BuildInfo]

  // Modify
  val updatedBuildInfo = buildInfo.copy(
    single_source = buildInfo.single_source.copy(url = url, sha1 = sha1)
  )

  // Save again.
  val prettyJson = Json.prettyPrint(Json.toJson(updatedBuildInfo))
  write.over(buildInfoPath, s"$prettyJson\n")
}

/**
 * Adds and commits changes to buildinfo.
 *
 * @param git Reference to git repository.
 * @param message The commit message used.
 *
 * @return Reference of the new commit.
 */
def commit(git: Git, message: String): RevCommit = {
  val buildinfoPath = dcosRepoPath / 'packages/ 'marathon / "buildinfo.json"

  git.add().addFilepattern("packages/marathon").call()
  git.commit().setMessage(message).call()
}

/**
 * Pushes commit to "marathon/latest".
 *
 * @param git Reference to git repository.
 * @param commitRev The reference of the commit which is pushed.
 */
def push(git : Git, commitRev: RevCommit): Unit = {
  git.push()
    .setRefSpecs(new RefSpec(s"${commitRev.getId.getName}:refs/heads/marathon/latest"))
    .call()
}

/**
 * Checks out a DC/OS repository and updates the Marathon package buildinfo.json
 * to the passed url and sha1.
 *
 * @param url The URL to the new Marathon artifact.
 * @param sha1 The sha1 checksum of the Marathon artifact.
 * @param message The commit message for the change.
 */
@main
def updateMarathon(url: String, sha1: String, message: String): Unit = {
  withRepository("git@github.com:mesosphere/dcos.git", "marathon/latest") { git =>
    upgradeDCOS(git)

    updateBuildInfo(url, sha1)

    val commitRev = commit(git, message)
    push(git, commitRev)
  }
}
