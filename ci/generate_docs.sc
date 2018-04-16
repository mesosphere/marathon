#!/usr/bin/env amm

import java.time.Instant
import ammonite.ops._
import $file.utils
import utils.SemVer

import $ivy.{
  `com.typesafe.akka::akka-http:10.0.11`,
  `com.typesafe.akka::akka-stream:2.5.9`
}

/**
  * Docs generation process:
  *
  * 1. copy docs/docs and docs/_layouts/docs.html part from 1.3, 1.4 and 1.5 branches to the temp dirs
  * 2. copy the latest release 1.5 branch doc to the temp dir
  * 3. generate the latest release docs
  * 4. replace docs/docs with docs/docs from 1.3, 1.4, 1.5 and generate docs for the _sites/docs_version
  */

val ignoredReleaseBranchesVersions = Set[String]()

val currentGitBranch = %%('git, "rev-parse", "--abbrev-ref", "HEAD")(pwd).out.lines.head

/**
  * This values will be used to override the tag for the respective release branch
  */
var overrideTargetVersions = Map.empty[String, GitCheckoutable]

@main
def main(release_commits_override: Map[String, String] = Map.empty) = {

  overrideTargetVersions = release_commits_override.mapValues(Commit)

  if (release_commits_override.nonEmpty) {
    println("Next commits will be used for docs generation:")
    println(release_commits_override.map(e => s"Version ${e._1}: ${e._2}").mkString("\n"))
  }


  val possiblyChangedLines: Vector[String] = %%('git, "status", "--porcelain")(pwd).out.lines.take(5)
  if (possiblyChangedLines.nonEmpty) {
    val msg = s"Git repository isn't clean, aborting docs generation. Changed files:\n${possiblyChangedLines.mkString("\n")}"
    println(msg)
    exit()
  }

  val docsBuildDir = makeTmpDir()
  val marathonDir = copyMarathon(docsBuildDir)

  val docsSourceDir = marathonDir/"docs"

  buildDocs(docsBuildDir, docsSourceDir, marathonDir)
  launchPreview(docsBuildDir)
}

def makeTmpDir(): Path = {
  val timestamp = Instant.now().toString.replace(':', '-')
  val path = root/"tmp"/s"marathon-docs-build-$timestamp"
  mkdir! path
  path
}

/**
  * Builds a docker image with Jekyll based on dockerfile in the docs path
  * @param docsPath
  */
def buildJekyllInDocker(docsPath: Path): Unit = {
  %("docker", "build", ".", "-t", "jekyll")(docsPath)
}

/**
  * List all tags in order
  * @param path path where git should be running
  * @return
  */
def listAllTagsInOrder(path: Path): Vector[String] = {
  %%('git, "tag", "-l", "--sort=version:refname")(path).out.lines
}

def isReleaseTag(tag: String) = tag.matches("""v[1-9]+\.\d+\.\d+""")

def notIgnoredBranch(branchVersion: String) = !ignoredReleaseBranchesVersions.contains(branchVersion)

def getLatestPatch(tags: Seq[SemVer]): SemVer = {
  tags.last
}

/**
  * getting 3 latest release versions and corresponding latest tags
  *
  * example: List[(String, String)] = List(("1.3", "1.3.14"), ("1.4", "1.4.11"), ("1.5", "1.5.6"))
  */
def docsTargetVersions(repoPath: Path): List[(String, GitCheckoutable)] = {
  listAllTagsInOrder(repoPath)
    .filter(isReleaseTag)
    .map(SemVer(_))
    .groupBy(version => s"${version.major}.${version.minor}")
    .filterKeys(notIgnoredBranch)
    .mapValues(getLatestPatch)
    .mapValues(Tag)
    .toList
    .sortBy(_._1)
    .takeRight(3)
    .map { case (releaseVersion, tag) =>
      val tagReplacement = overrideTargetVersions.getOrElse(releaseVersion, tag)
      releaseVersion -> tagReplacement
    }
}

/**
  * launches a docer container with jekyll and generates docs located in folder docsPath
  * @param docsPath path with docs
  * @param maybeVersion versioned docs to be generated (if any)
  */
def generateDocsByDocker(docsPath: Path, maybeVersion: Option[String]): Unit = {
  maybeVersion match {
    case Some(version) => //generating versioned docs
      %("docker", "run", "-e", s"MARATHON_DOCS_VERSION=$version", "--rm", "-it", "-v", s"$docsPath:/site-docs", "jekyll")(docsPath)

    case None => //generating top-level docs
      %("docker", "run", "--rm", "-it", "-v", s"$docsPath:/site-docs", "jekyll")(docsPath)
  }
}

// step 1: copy docs/docs to the respective dirs

def checkoutDocsToTempFolder(buildDir: Path, docsDir: Path, checkedRepoDir: Path): Seq[(String, Path)] = {
  docsTargetVersions(checkedRepoDir) map { case (releaseBranchVersion, tagVersion) =>
    val tagName = tagVersion.gitCheckoutString
    %('git, 'checkout, s"$tagName")(checkedRepoDir)
    val tagBuildDir = buildDir / releaseBranchVersion
    mkdir! tagBuildDir
    println(s"Copying $tagName docs to: $tagBuildDir")
    cp.into(docsDir / 'docs, tagBuildDir)
    cp.into(docsDir / '_layouts / "docs.html", tagBuildDir)
    println(s"Docs folder for $releaseBranchVersion is copied to ${tagBuildDir / "docs"}")
    releaseBranchVersion -> tagBuildDir
  }
}

def prepareDockerImage(buildDir: Path, docsDir: Path): Unit = {
  val imagePreparationWorkingDir = buildDir / 'docs
  println(s"Preparing docker image in $imagePreparationWorkingDir")
  cp.into(docsDir, buildDir)
  buildJekyllInDocker(buildDir / 'docs)

  println("Docker image preparation is done. Cleaning after preparation ...")
  rm ! imagePreparationWorkingDir
}

/**
  * Generates latest docs which can be found at /marathon/docs
  */
def generateTopLevelDocs(buildDir: Path, docsDir: Path, checkedRepoDir: Path) = {
  val topLevelGeneratedDocsDir = buildDir / 'docs

  val targetVersions = docsTargetVersions(checkedRepoDir)

  val (_, latestReleaseVersion) = targetVersions.last


  %.git('checkout, latestReleaseVersion.gitCheckoutString)(checkedRepoDir)

  println(s"Copying docs for ${latestReleaseVersion.gitCheckoutString} into $buildDir")
  cp.into(docsDir, buildDir)

  println("Cleaning previously generated docs")
  rm! topLevelGeneratedDocsDir / '_site

  println(s"Generating root docs for ${latestReleaseVersion.gitCheckoutString}")
  println(topLevelGeneratedDocsDir)
  write.append(topLevelGeneratedDocsDir / "_config.yml", s"release_versions: [${targetVersions.reverse.map(_._1).mkString(", ")}]")
  generateDocsByDocker(topLevelGeneratedDocsDir, None)
}

/**
  * Generates docs for the respective marathon versions, for example:
  * marathon/1.6/docs
  * marathon/1.5/docs
  * marathon/1.4/docs
  */
def generateVersionedDocs(buildDir: Path, versionedDocsDirs: Seq[(String, Path)]) {
  val rootDocsDir = buildDir / 'docs
  println(s"Generating docs for versions ${versionedDocsDirs.map(_._1).mkString(", ")}")
  versionedDocsDirs foreach { case (releaseBranchVersion, path) =>
    println("Cleaning docs/docs")
    rm! rootDocsDir / 'docs
    println(s"Copying docs for $releaseBranchVersion to the docs/docs folder")
    cp.into(path / 'docs, rootDocsDir)
    cp.over(path / "docs.html", rootDocsDir / '_layouts / "docs.html")
    println(s"Generation docs for $releaseBranchVersion")
    write.over(rootDocsDir / s"_config.$releaseBranchVersion.yml",
      s"""baseurl : /marathon/$releaseBranchVersion
         |release_versions: [${versionedDocsDirs.reverse.map(_._1).mkString(", ")}]
       """.stripMargin
    )
    generateDocsByDocker(rootDocsDir, Some(releaseBranchVersion))
  }
}

def launchPreview(buildDir: Path): Unit = {
  import akka.actor.ActorSystem
  import akka.http.scaladsl.Http
  import akka.http.scaladsl.server._
  import akka.http.scaladsl.model._
  import akka.http.scaladsl.server.Directives._
  import akka.stream.ActorMaterializer
  import scala.io.StdIn

  implicit val system = ActorSystem("docs-generation-script")
  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  val siteDir = (buildDir/'docs/'_site).toString()

  val route =
    pathSingleSlash {
      get {
        redirect("marathon/", StatusCodes.PermanentRedirect)
      }
    } ~ pathPrefix("marathon") {
      pathSuffixTest(PathMatchers.Slash) {
        mapUnmatchedPath(path => path / "index.html") {
          getFromDirectory(siteDir)
        }
      } ~ redirectToTrailingSlashIfMissing(StatusCodes.PermanentRedirect) {
        getFromDirectory(siteDir)
      }
    }


  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Success! Docs were generated at $siteDir\nYou can browse them at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate()) // and shutdown when done
}

def copyMarathon(buildDir: Path): Path = {
  println(s"Copying marathon folder into $buildDir")
  cp.into(pwd/up, buildDir)
  println("Done.")
  buildDir / 'marathon
}

def buildDocs(buildDir: Path, docsDir: Path, checkedRepoDir: Path) = {
  prepareDockerImage(buildDir, docsDir)
  val versionedDocsDirs = checkoutDocsToTempFolder(buildDir, docsDir, checkedRepoDir)
  generateTopLevelDocs(buildDir, docsDir, checkedRepoDir)
  generateVersionedDocs(buildDir, versionedDocsDirs)
}

trait GitCheckoutable {
  def gitCheckoutString: String
}

case class Tag(semVer: SemVer) extends GitCheckoutable {
  override def gitCheckoutString = s"tags/${semVer.toTagString}"
}

case class Commit(commit: String) extends GitCheckoutable {
  override def gitCheckoutString = commit
}