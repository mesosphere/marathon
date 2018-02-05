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

val ignoredVersions = Set("1.6.0")

/**
  * getting 3 latest releases tags to build docs for
  */
val docsTargetVersions = %%('git, "tag", "-l", "--sort=version:refname").out.lines.filter(_.matches("""v[1-9]+\.\d+\.\d+""")).groupBy(v => v.drop(1).takeWhile {
  var reachedMinorVersion = false
  elem => elem match {
    case c if c.isDigit => true
    case c if c == '.' && !reachedMinorVersion =>
      reachedMinorVersion = true
      true
    case _ => false
  }
}).mapValues{ tags => tags.last.drop(1)}.toList.sortBy(_._1).map(_._2).filterNot(v => ignoredVersions.contains(v)).reverse.take(3).reverse

val latestReleaseVersion = docsTargetVersions.last

val buildDir = makeTmpDir()

val docsDir = pwd/up/"docs"

def generateDocsForVersion(docsPath: Path, version: String, outputPath: String = "_site"): Unit = {
  %('bundle, "exec", s"jekyll build --config _config.yml,_config.$version.yml -d $outputPath/$version/")(docsPath)
}

def tagNameForVersion(version: String) = {
  s"tags/v$version"
}


// step 1: copy docs/docs to the respective dirs

docsTargetVersions foreach { version =>
  val tagName = tagNameForVersion(version)

  %git('checkout, s"$tagName")

  val tagBuildDir = buildDir/version

  mkdir! tagBuildDir

  println(s"Copying $version docs to: $tagBuildDir")

  cp.into(docsDir/'docs, tagBuildDir)

  cp.into(docsDir/'_layouts/"docs.html", tagBuildDir)

  println(s"Docs folder for $version is copied to ${tagBuildDir / "docs"}")

}

// step 2: generate the default version (latest release)

%git('checkout, tagNameForVersion(latestReleaseVersion))


println(s"Copying docs for $latestReleaseVersion into $buildDir")

cp.into(docsDir, buildDir)

val rootDocsDir = buildDir / 'docs

println("Cleaning previously generated docs")
rm! rootDocsDir / '_site

println(s"Generating root docs for $latestReleaseVersion")
%("bundle", "install", "--path", s"vendor/bundle")(rootDocsDir)
%('bundle, "exec", s"jekyll build --config _config.yml -d _site")(rootDocsDir)


// step 3: generate docs for other versions
println(s"Generating docs for versions ${docsTargetVersions.mkString(", ")}")
docsTargetVersions foreach { version =>

  println("Cleaning docs/docs")
  rm! rootDocsDir / 'docs
  println(s"Copying docs for $version to the docs/docs folder")
  cp.into(buildDir/version/'docs, rootDocsDir)
  cp.over(buildDir/version/"docs.html", rootDocsDir/'_layouts/"docs.html")
  println(s"Generation docs for $version")
  write.over(rootDocsDir/s"_config.$version.yml", s"baseurl : /marathon/$version")
  generateDocsForVersion(rootDocsDir, version)
}


println(s"Success! Docs generated at $rootDocsDir")
