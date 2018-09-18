#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import scala.io.Source
import scala.util.Try

import $file.utils
import utils.SemVer

// output directory for all packages going to S3
val PACKAGE_DIR: Path = pwd / 'target / 'universal
// dcos catalog template directory
val TEMPLATE_DIR: Path = pwd / 'dcos
// versioned dcos catalog directory
val UNIVERSE_DIR: Path = pwd / 'target / 'dcos

@main
def localBuild(version: String) {
  buildRegistry(SemVer(version, ""))
}

/**
 * Main function for building DCOS registry file that works
 * with DCOS 1.12+.
 *
 * @param version updated in the DCOS package files
 */

def buildRegistry(version: SemVer) {
  createCatalogPackage(version)
  val packagingTool = getPackagingTool()
  buildDCOS(packagingTool, version)
}

/**
 * Creates catalog package for Marathon with version info
 * replaces __FILL_IN_VERSION_HERE__ with provided version
 *
 * @param version updated in the DCOS package files
 */
def createCatalogPackage(version: SemVer) {
  rm! UNIVERSE_DIR
  mkdir! UNIVERSE_DIR

  // list all files in temp
  TEMPLATE_DIR.toIO.listFiles.foreach(file => {
    val catalogFile = UNIVERSE_DIR / file.getName

    Source.fromFile(file).getLines.map {
      x => if(x.contains("__FILL_IN_VERSION_HERE__")) x.replace("__FILL_IN_VERSION_HERE__", version.toReleaseString) else x }
      .foreach(x => write.append(catalogFile, x + System.lineSeparator()))
    }
  )
}

/**
 * Pulls the registry build tool for the specific OS.
 * Uses the marathon version which contains the "--use-local"
 *
 */

def getPackagingTool(): Path = {
  val os = %%("uname", "-s").out.string.trim.toLowerCase
  val packageFile = s"dcos-registry-$os"
  val packagePath = pwd / packageFile
  if( exists! packagePath) { println("using cached packing tool")}
  else {
    val download = s"https://downloads.mesosphere.io/marathon/package-registry/binaries/cli/darwin/x86-64/$packageFile"
    %("curl", "-O", download)
    %("chmod", "+x", packagePath)
  }
  return packagePath
}

/**
 * Creates DCOS package for Marathon
 * replaces __FILL_IN_VERSION_HERE__ with provided version
 *
 * @param packagingTool the registry build tool
 * @param version used to identify the correct build file
 */

def buildDCOS(packagingTool: Path, version: SemVer) {
  // ./dcos-registry-darwin registry migrate --package-directory=repo --output-directory=target
  Try(%(packagingTool, "registry", "migrate", s"--package-directory=$UNIVERSE_DIR", s"--output-directory=$PACKAGE_DIR"))
  val registryJSON = PACKAGE_DIR / s"marathon-${version.toReleaseString}.json"
  // ./dcos-registry-darwin registry build --build-definition-file=target/marathon-1.7.50.json --output-directory=target --use-local
  %(packagingTool, "registry", "build", s"--build-definition-file=$registryJSON", s"--output-directory=$PACKAGE_DIR", "--use-local")
}
