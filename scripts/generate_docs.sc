#!/usr/bin/env amm

import java.time.Instant
import ammonite.ops._
import ammonite.ops.ImplicitWd._

/**
  * Docs generation process:
  *
  * 1. copy docs/docs and docs/_layouts/docs.html part from 1.3, 1.4 and 1.5 branches to the temp dirs
  * 2. copy the latest release (1.5_ branch doc to the temp dir
  * 3. generate the latest release docs
  * 4. replace docs/docs with docs/docs from 1.3, 1.4, 1.5 and generate docs for the _sites/docs_version
  */

def makeTmpDir(): Path = {
  val timestamp = Instant.now().toString.replace(':', '-')
  val path = root/"tmp"/s"marathon-docs-build-$timestamp"
  mkdir! path
  path
}

val ignoredReleaseBranchesVersions = Set("1.6")

def listAllTagsInOrder: Vector[String] = %%('git, "tag", "-l", "--sort=version:refname").out.lines

def isReleaseTag(tag: String) = tag.matches("""v[1-9]+\.\d+\.\d+""")

/**
  * get the major.minor version from a [v]major.minor.patch
  *
  * v1.4.11 -> 1.4
  * 1.45.983 -> 1.45
  *
  * @param tag
  * @return
  */
def releaseBranchVersion(tag: String) = tag.dropWhile(_.isLetter).takeWhile {
  var reachedMinorVersion = false
  elem => elem match {
    case c if c.isDigit => true
    case c if c == '.' && !reachedMinorVersion =>
      reachedMinorVersion = true
      true
    case _ => false
  }
}

def notIgnoredBranch(branchVersion: String) = !ignoredReleaseBranchesVersions.contains(branchVersion)

def getTheLastTagVersion(tags: Seq[String]) = tags.last.drop(1)

/**
  * getting 3 latest release versions and corresponding latest tags
  *
  * example: List[(String, String)] = List(("1.3", "1.3.14"), ("1.4", "1.4.11"), ("1.5", "1.5.6"))
  */
val docsTargetVersions = listAllTagsInOrder
  .filter(isReleaseTag)
  .groupBy(releaseBranchVersion)
  .filterKeys(notIgnoredBranch)
  .mapValues(getTheLastTagVersion)
  .toList.sortBy(_._1)
  .takeRight(3)

val (latestReleaseBranch, latestReleaseVersion) = docsTargetVersions.last

val buildDir = makeTmpDir()

val docsDir = pwd/up/"docs"

def generateDocsForVersion(docsPath: Path, version: String, outputPath: String = "_site"): Unit = {
  %('bundle, "exec", s"jekyll build --config _config.yml,_config.$version.yml -d $outputPath/$version/")(docsPath)
}

def branchForTag(version: String) = {
  s"tags/v$version"
}


%git 'fetch

// step 1: copy docs/docs to the respective dirs

docsTargetVersions foreach { case (releaseBranchVersion, tagVersion) =>
  val tagName = branchForTag(tagVersion)

  %git('checkout, s"$tagName")

  %git('pull)

  val tagBuildDir = buildDir/releaseBranchVersion

  mkdir! tagBuildDir

  println(s"Copying $tagVersion docs to: $tagBuildDir")

  cp.into(docsDir/'docs, tagBuildDir)

  cp.into(docsDir/'_layouts/"docs.html", tagBuildDir)

  println(s"Docs folder for $releaseBranchVersion is copied to ${tagBuildDir / "docs"}")

}

// step 2: generate the default version (latest release tag)

%git('checkout, branchForTag(latestReleaseVersion))


println(s"Copying docs for $latestReleaseVersion into $buildDir")

cp.into(docsDir, buildDir)

val rootDocsDir = buildDir / 'docs

println("Cleaning previously generated docs")
rm! rootDocsDir / '_site

println(s"Generating root docs for $latestReleaseVersion")
%("bundle", "install", "--path", s"vendor/bundle")(rootDocsDir)
%('bundle, "exec", s"jekyll build --config _config.yml -d _site")(rootDocsDir)


// step 3: generate docs for other versions
println(s"Generating docs for versions ${docsTargetVersions.map(_._2).mkString(", ")}")
docsTargetVersions foreach { case (releaseBranchVersion, tagVersion) =>

  println("Cleaning docs/docs")
  rm! rootDocsDir / 'docs
  println(s"Copying docs for $tagVersion to the docs/docs folder")
  cp.into(buildDir/releaseBranchVersion/'docs, rootDocsDir)
  cp.over(buildDir/releaseBranchVersion/"docs.html", rootDocsDir/'_layouts/"docs.html")
  println(s"Generation docs for $tagVersion")
  write.over(rootDocsDir/s"_config.$releaseBranchVersion.yml", s"baseurl : /marathon/$releaseBranchVersion")
  generateDocsForVersion(rootDocsDir, releaseBranchVersion)
}


println(s"Success! Docs generated at $rootDocsDir")
