#!/usr/bin/env amm

import $ivy.`com.typesafe.play::play-json:2.6.0`
import $ivy.`org.eclipse.jgit:org.eclipse.jgit:4.8.0.201706111038-r`

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.transport.{ CredentialsProvider, PushResult, RefSpec,
  RemoteRefUpdate, URIish, UsernamePasswordCredentialsProvider }
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
def withRepository(repo: String, branch: String)(f: Git => Unit)(implicit creds: CredentialsProvider): Unit = {
  // Make sure the path is empty for cloning.
  rm! dcosRepoPath

  // Clone
  val git = Git.cloneRepository()
    .setURI(repo)
    .setRemote("mesosphere")
    .setBranch(branch)
    .setCredentialsProvider(creds)
    .setDirectory(dcosRepoPath.toIO)
    .call()

  println(s"Cloned $repo:$branch.")

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
def upgradeDCOS(git: Git)(implicit creds: CredentialsProvider): Unit = {
  // Update with latest DC/OS
  val remoteAddCmd = git.remoteAdd()
  remoteAddCmd.setName("dcos")
  remoteAddCmd.setUri(new URIish("https://github.com/dcos/dcos.git"))
  remoteAddCmd.call()

  git.pull()
    .setCredentialsProvider(creds)
    .setRemote("dcos")
    .setRemoteBranchName("master")
    .setStrategy(MergeStrategy.THEIRS)
    .call()

  println(s"Merged DC/OS master.")
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
  println(s"Commit changes: $message.")

  val buildinfoPath = dcosRepoPath / 'packages/ 'marathon / "buildinfo.json"

  git.add().addFilepattern("packages/marathon").call()
  git.commit().setMessage(message).call()
}


/**
 * Exception thrown by `push(2)` if the update was not successful.
 */
case class PushException(val msg: String, private val cause: Throwable = None.orNull) extends Exception(msg, cause)

/**
 * @return true if [status] is OK or UP_TO_DATE, false otherwise.
 */
def notOk(status: RemoteRefUpdate.Status): Boolean = {
  !(RemoteRefUpdate.Status.OK.equals(status) || RemoteRefUpdate.Status.UP_TO_DATE.equals(status))
}

/**
 * Throws a [PushException] is push result contains a RemoteRefUpdata.Status
 * other than OK and UP_TO_DATE.
 *
 * @param result The result of a PushCommand call.
 * @param refRpec The ref spec that was pushed.
 */
def throwIfNotSuccessful(refSpec: RefSpec)(result: PushResult): Unit = {
  import scala.collection.JavaConverters._

  result.getRemoteUpdates().asScala.foreach { refUpdate =>
    val status = refUpdate.getStatus()
    if(notOk(status)) throw new PushException(s"Could not push $refSpec: ${refUpdate.getMessage()}")
  }
}

/**
 * Pushes commit to "marathon/latest".
 *
 * @param git Reference to git repository.
 * @param commitRev The reference of the commit which is pushed.
 */
def push(git : Git, commitRev: RevCommit)(implicit creds: CredentialsProvider): Unit = {
  import scala.collection.JavaConverters._

  val refSpec = (new RefSpec())
    .setSource(commitRev.getId.getName)
    .setDestination("refs/heads/marathon/latest")

  val result = git.push()
    .setRemote("mesosphere")
    .setRefSpecs(refSpec)
    .setCredentialsProvider(creds)
    .call()

  result.asScala.foreach(throwIfNotSuccessful(refSpec))
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

  // Authentication
  val user = sys.env("GIT_USER")
  val password = sys.env("GIT_PASSWORD")
  implicit val credentials = new UsernamePasswordCredentialsProvider(user, password)

  println(s"Updating Marathon to $url in DC/OS.")

  withRepository("https://github.com/mesosphere/dcos.git", "marathon/latest") { git =>
    upgradeDCOS(git)

    updateBuildInfo(url, sha1)

    val commitRev = commit(git, message)
    push(git, commitRev)

    %('git, "log", "-5")(pwd / 'dcos)
  }
}
