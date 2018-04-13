import java.time.Instant

import $ivy.`com.softwaremill.sttp::core:1.1.12`
import $ivy.`com.typesafe.play::play-json:2.6.9`

import $file.ui
import $file.tokens
import $file.changelog
import $file.github
import $file.jenkins
import $file.context
import context._
import changelog.SemVer
import ammonite.ops._
import tokens.Credentials

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
import ammonite.ops._

type BranchName = String

def checkIfShouldStartRelease(latestReleasedVersion: SemVer)(implicit buildContext: BuildContext) = {

  implicit val path = buildContext.marathon.directory.path

  val latestReleasedVersionString = latestReleasedVersion.toTagString()

  val gitLogOutput = %%('git, 'log, s"$latestReleasedVersionString..releases/1.5").out.lines

  if (gitLogOutput.map(_.trim).filter(_.nonEmpty).isEmpty) {
    println("Nothing to release")
    exit()
  }
}

def commitAndPushChangelog(nextVersion: SemVer)(implicit context: BuildContext): BranchName = {
  implicit val path = context.marathon.directory.path
  val stringVersion = nextVersion.toReleaseString()
  val branchName = s"changelog-update-$stringVersion"
  %('git, 'checkout, "-b", branchName)
  %('git, 'add, ".")
  %('git, 'commit, "-m", s""" "changelog update for $stringVersion" """)
  %('git, 'push, "origin", branchName)
  branchName
}

def createAndPushTag(version: SemVer)(implicit buildContext: BuildContext): Unit = {
  implicit val path = buildContext.marathon.directory.path
  %('git, 'pull, "origin", "releases/1.5")
  %('git, 'tag, "-a", version.toTagString(), "-m", version.toTagString())
  %('git, 'push, "--tags")
}


def updateChangelog(changeLog: String)(implicit buildContext: BuildContext): Unit = {
  val changelogDir = buildContext.marathon.directory.path
  mv(changelogDir/"changelog.md", changelogDir/"changelog.md.old")
  write.over(changelogDir/"changelog.md", changeLog)
  write.append(changelogDir/"changelog.md", read(changelogDir/"changelog.md.old"))
  rm(changelogDir/"changelog.md.old")
  println("Generated changelog:")
  println(changeLog)
  println(s"Updated changelog can be found here: ${changelogDir / "changelog.md"}")
}

@main
def main(): Unit = {

  println("creating a temp directory")
  val tempDir = makeTmpDir()
  println(s"temp directory created at $tempDir")


  val marathonRepoName = "marathon"
  val universeRepoName = "universe"
  val dcosOssRepoName = "dcos"
  val dcosEeRepoName = "dcos-ee"

  implicit val context = BuildContext(
    WorkingDirectory(tempDir),
    MarathonContext(GitRepository("mesosphere", marathonRepoName), MarathonRepoDirectory(tempDir / marathonRepoName)),
    UniverseContext(GitRepository("mesosphere", universeRepoName), UniverseRepoDirectory(tempDir / universeRepoName)),
    DcosContext(GitRepository("dcos", dcosOssRepoName), DcosOssRepoDirectory(tempDir / dcosOssRepoName)),
    DcosEeContext(GitRepository("mesosphere", dcosEeRepoName), DcosEeRepoDirectory(tempDir / dcosEeRepoName))
  )

  val reviewers = Seq("jeschkies", "ichernetsky", "alenkacz", "timcharper", "kensipe", "wavesoft", "zen-dog")

  val credentials = tokens.loadUserCredentials()
  println(s"Credentials are successfully loaded: $credentials")

  val githubClient = new github.GithubClient(credentials.github)
  val jenkinsClient = new jenkins.JenkinsClient(credentials.jenkins._1, credentials.jenkins._2)


  checkoutMarathonRepo()


  implicit val marathonRepoDirectory = context.marathon.directory.path


  val latestReleasedVerison = {
    val lastReleasedTag = %%('git, "tag", "-l", "--sort=version:refname")(marathonRepoDirectory).out.lines.filter(_.contains("v1.5")).last
    SemVer(lastReleasedTag)
  }

  println(s"Latest released marathon 1.5 version is ${latestReleasedVerison.toReleaseString()}")

  val nextMarathonVersion = latestReleasedVerison.nextBuildVersion

  checkIfShouldStartRelease(latestReleasedVerison)


  val changeLog = changelog.generateChangelog()
  updateChangelog(changeLog)
  println("Please review the generated changelog and make necessary changes. Once you're done, hit Enter.")
  scala.io.StdIn.readLine()
  println("Commiting changes")
  val branchName = commitAndPushChangelog(nextMarathonVersion)
  println("Creating a marathon PR")
  val prNumber = githubClient.createMarathonChangelog15PR(nextMarathonVersion.toReleaseString(), branchName)
  githubClient.requestReviews(prNumber, reviewers)
  ui.showSpinner("Waiting for the PR to be merged") {
    githubClient.waitUntilMerged(prNumber)
  }
  val sha = githubClient.getMergeCommitSha(prNumber).get

  println(sha)

  //todo: Implement running a jenkins job
  //  val queueId = jenkinsClient.start15ReleasePipeline(sha, nextMarathonVersion.toTagString())
  //  jenkinsClient.waitForTheJenkinsBuild(queueId)



  println("Creating a new tag and pushing it")
  createAndPushTag(nextMarathonVersion)
  println("Creating new release")
  githubClient.createReleaseFromTag(nextMarathonVersion.toTagString(), changeLog)

  //todo: Build mom ee here





}


def makeTmpDir(): Path = {
  val timestamp = Instant.now().toString.replace(':', '-')
  val path = root/"tmp"/s"marathon-release-1.5-$timestamp"
  mkdir! path
  path
}

def checkoutMarathonRepo()(implicit buildContext: BuildContext): Unit = {
  %('git,
    'clone, s"git@github.com:${buildContext.marathon.repository.owner}/${buildContext.marathon.repository.name}.git",
    "-b", "releases/1.5")(buildContext.workingDirectory.path)
  %%('git, 'fetch, "--tags")(buildContext.marathon.directory.path)
}


def checkoutUniverse()(implicit buildContext: BuildContext): Unit = {
  %('git,
    'clone, s"git@github.com:${buildContext.universe.repository.owner}/${buildContext.universe.repository.name}.git",
    "-b", "version-3.x")(buildContext.workingDirectory.path)
}

def checkoutDcos()(implicit buildContext: BuildContext): Unit = {
  %('git,
    'clone, s"git@github.com:${buildContext.dcos.repository.owner}/${buildContext.dcos.repository.name}.git",
    "-b", "version-3.x")(buildContext.workingDirectory.path)
}

