#!/usr/bin/env amm

import java.time.Instant
import ammonite.ops._
import ammonite.ops.ImplicitWd._

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

val ignoredReleaseBranchesVersions = Set("1.6")

val currentGitBranch = %%('git, "rev-parse", "--abbrev-ref", "HEAD").out.lines.head

@main
def main(tracked_by_git: Boolean = true) = {
  if (tracked_by_git) {
    // If the script is being tracked by git, we should create and run an untracked copy of it in order
    // to avoid errors during branch checkout
    cp.over(pwd/"generate_docs.sc", pwd/"generate_docs.temp")
    %('amm, "generate_docs.temp", "--tracked_by_git", "false")
    rm! pwd/"generate_docs.temp"
  } else {
    // check that repo is clean
    val possiblyChangedLines: Vector[String] = %%('git, "diff-files", "--name-only").out.lines
    if (possiblyChangedLines.nonEmpty) {
      val msg = s"Git repository isn't clean, aborting docs generation. Changed files:\n${possiblyChangedLines.mkString("\n")}"
      println(msg)
      exit()
    }

    val docsBuildDir = makeTmpDir()
    val docsSourceDir = pwd/up/"docs"

    buildDocs(docsBuildDir, docsSourceDir)
    launchPreview(docsBuildDir)
    %('git, 'checkout, currentGitBranch)
  }
}

def makeTmpDir(): Path = {
  val timestamp = Instant.now().toString.replace(':', '-')
  val path = root/"tmp"/s"marathon-docs-build-$timestamp"
  mkdir! path
  path
}

def buildJekyllInDocker(docsPath: Path): Unit = {
  %("docker", "build", ".", "-t", "jekyll")(docsPath)
}

def listAllTagsInOrder: Vector[String] = %%('git, "tag", "-l", "--sort=version:refname").out.lines

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
val docsTargetVersions: List[(String, SemVer)] = listAllTagsInOrder
  .filter(isReleaseTag)
  .map(SemVer(_))
  .groupBy(version => s"${version.major}.${version.minor}")
  .filterKeys(notIgnoredBranch)
  .mapValues(getLatestPatch)
  .toList
  .sortBy(_._1)
  .takeRight(3)

val (latestReleaseBranch, latestReleaseVersion) = docsTargetVersions.last

def generateDocsByDocker(docsPath: Path, maybeVersion: Option[String]): Unit = {
  maybeVersion match {
    case Some(version) => //generating versioned docs
      %("docker", "run", "-e", s"MARATHON_DOCS_VERSION=$version", "--rm", "-it", "-v", s"$docsPath:/site-docs", "jekyll")(docsPath)

    case None => //generating top-level docs
      %("docker", "run", "--rm", "-it", "-v", s"$docsPath:/site-docs", "jekyll")(docsPath)
  }
}

def branchForTag(version: SemVer) = {
  s"tags/${version.toTagString}"
}

// step 1: copy docs/docs to the respective dirs

def checkoutDocsToTempFolder(buildDir: Path, docsDir: Path): Seq[(String, Path)] = {
  docsTargetVersions map { case (releaseBranchVersion, tagVersion) =>
    val tagName = branchForTag(tagVersion)
    %git('checkout, s"$tagName")
    val tagBuildDir = buildDir / releaseBranchVersion
    mkdir! tagBuildDir
    println(s"Copying ${tagVersion.toReleaseString} docs to: $tagBuildDir")
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

def generateTopLevelDocs(buildDir: Path, docsDir: Path) = {
  val topLevelGeneratedDocsDir = buildDir / 'docs

  %git('checkout, branchForTag(latestReleaseVersion))

  println(s"Copying docs for ${latestReleaseVersion.toReleaseString} into $buildDir")
  cp.into(docsDir, buildDir)

  println("Cleaning previously generated docs")
  rm! topLevelGeneratedDocsDir / '_site

  println(s"Generating root docs for ${latestReleaseVersion.toReleaseString}")
  println(topLevelGeneratedDocsDir)
  generateDocsByDocker(topLevelGeneratedDocsDir, None)
}

def generateVersionedDocs(buildDir: Path, versionedDocsDirs: Seq[(String, Path)]) {
  val rootDocsDir = buildDir / 'docs
  println(s"Generating docs for versions ${docsTargetVersions.map(_._2.toReleaseString).mkString(", ")}")
  versionedDocsDirs foreach { case (releaseBranchVersion, path) =>
    println("Cleaning docs/docs")
    rm! rootDocsDir / 'docs
    println(s"Copying docs for $releaseBranchVersion to the docs/docs folder")
    cp.into(path / 'docs, rootDocsDir)
    cp.over(path / "docs.html", rootDocsDir / '_layouts / "docs.html")
    println(s"Generation docs for $releaseBranchVersion")
    write.over(rootDocsDir / s"_config.$releaseBranchVersion.yml", s"baseurl : /marathon/$releaseBranchVersion")
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
      } ~ getFromDirectory(siteDir)
    }


  val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  println(s"Success! Docs were generated at $siteDir\nYou can browse them at http://localhost:8080/\nPress RETURN to stop...")
  StdIn.readLine() // let it run until user presses return
  bindingFuture.flatMap(_.unbind()).onComplete(_ => system.terminate()) // and shutdown when done
}

def buildDocs(buildDir: Path, docsDir: Path) = {
  prepareDockerImage(buildDir, docsDir)
  val versionedDocsDirs = checkoutDocsToTempFolder(buildDir, docsDir)
  generateTopLevelDocs(buildDir, docsDir)
  generateVersionedDocs(buildDir, versionedDocsDirs)
}
