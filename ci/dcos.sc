#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import scala.io.Source

import $file.utils
import utils.{SemVer, SemVerRead}


/**
 * Main function for building DCOS registry file that works
 * with DCOS 1.12+.
 *
 * @param version updated in the DCOS package files
 */
@main
def buildRegistry(version: SemVer) = {
  val universePath = createCatalogPackage(version)
  val packagingTool = getPackagingTool()
  buildDCOSRegistryFile(packagingTool, universePath, version)
}

/**
 * Creates catalog package for Marathon with version info
 * replaces __FILL_IN_VERSION_HERE__ with provided version
 *
 * @param version updated in the DCOS package files
 * @param universePath location to build out the dcos universe files
 */
def createCatalogPackage(
  version: SemVer,
  universePath: Path = pwd / 'target / 'dcos
): Path = {
  rm! universePath
  mkdir! universePath

  // dcos catalog template directory
  val templateDir: Path = pwd / 'dcos

  // list all files in temp
  templateDir.toIO.listFiles.foreach(file => {
    val catalogFile = universePath / file.getName

    Source.fromFile(file).getLines.map {
      x => if(x.contains("__FILL_IN_VERSION_HERE__")) x.replace("__FILL_IN_VERSION_HERE__", version.toReleaseString) else x }
      .foreach(x => write.append(catalogFile, x + System.lineSeparator()))
    }
  )
  universePath
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
def buildDCOSRegistryFile(
  packagingTool: Path,
  universePath: Path,
  version: SemVer) = {

  // output directory for all packages going to S3
  val packageDir: Path = pwd / 'target / 'universal
  // ./dcos-registry-darwin registry migrate --package-directory=repo --output-directory=target
  %(packagingTool, "registry", "migrate", s"--package-directory=$universePath", s"--output-directory=$packageDir")
  val registryJSON = packageDir / s"marathon-${version.toReleaseString}.json"
  // ./dcos-registry-darwin registry build --build-definition-file=target/marathon-1.7.50.json --output-directory=target --use-local
  %(packagingTool, "registry", "build", s"--build-definition-file=$registryJSON", s"--output-directory=$packageDir", "--use-local")
}
