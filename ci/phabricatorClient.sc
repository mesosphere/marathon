#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scalaj.http._
import upickle._

// This Phabiractor client follows roughly the patterns from Uber's client for
// their Jenkins Plugin:
// https://github.com/uber/phabricator-jenkins-plugin/blob/master/src/main/java/com/uber/jenkins/phabricator/conduit/ConduitAPIClient.java.

/**
 * Execute Conduit method.
 *
 * @param method Method name, e.g. differential.revision.edit.
 * @param parameters JSON encoded method parameters.
 *     E.g. { "trancations": [{"type":"accept"}], "objectIdentifier":"foo" }
 */
def execute(method: String, parameters: Js.Obj): Unit = {
  // Add API token to parameters
  val PHABRICATOR_API_TOKEN =
    sys.env.getOrElse("PHABRICATOR_API_TOKEN", throw new IllegalArgumentException("PHABRICATOR_API_TOKEN enviroment variable was not set."))

  val extendedParameters = parameters.obj + ("__conduit__" -> Js.Obj("token" -> Js.Str(PHABRICATOR_API_TOKEN)))
  val requestParameters: Js.Obj = Js.Obj( extendedParameters.toSeq: _* )

  // Execute request
  val response = Http(s"https://phabricator.mesosphere.com/api/${method}")
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .postForm(Seq( "params" -> requestParameters.toString ))
    .asString
    .throwError
}

/**
 * Accept revision revisionId.
 */
def accept(revisionId: String): Unit = {
  execute(
    "differential.revision.edit",
    Js.Obj(
      "transactions" -> Js.Arr( Js.Obj("type" -> Js.Str("accept")) ),
      "objectIdentifier" -> Js.Str(revisionId)
    )
  )
}

/**
 * Reject revision revisionId.
 */
def reject(revisionId: String): Unit = {
  execute(
    "differential.revision.edit",
    Js.Obj(
      "transactions" -> Js.Arr( Js.Obj("type" -> Js.Str("reject")) ),
      "objectIdentifier" -> Js.Str(revisionId)
    )
  )
}

/**
 * Comment with msg on revision revisionId.
 */
def comment(revisionId: String, msg: String): Unit = {
  execute(
    "differential.revision.edit",
    Js.Obj(
      "transactions" -> Js.Arr(
        Js.Obj(
          "type" -> Js.Str("comment"),
          "value" -> Js.Str(msg)
        )
      ),
      "objectIdentifier" -> Js.Str(revisionId)
    )
  )
}

/**
 * Report test results to revision.
 *
 * @param phid PHID passed by Harbormaster.
 * @param status The status of the build for Phabriactor. Must be "fail" or "pass"
 */
@main
def reportTestResults(phid: String, status: String): Unit = {
  require("fail" == status || "pass" == status)

  // Join all results
  val testResults = ls! pwd / 'target / "phabricator-test-reports" |? ( _.ext == "json")
  val joinedTestResults: Js.Arr = testResults.view.map(read!)
    .map(upickle.json.read)
    .collect { case a: Js.Arr => a }
    .reduce { (l: Js.Arr, r: Js.Arr) =>
      val n = l.arr ++ r.arr
      Js.Arr(n :_*)
    }

  // Add PHID and status
  val parameters = Js.Obj(
    "buildTargetPHID" -> Js.Str(phid),
    "type" -> Js.Str(status.toString),
    "unit" -> joinedTestResults
  )
  execute("harbormaster.sendmessage", parameters)
}

/**
 * Report success of diff build back to Phabricator.
 *
 * @param diffId The differential ID of the build.
 * @param phId PHID passed by Harbormaster to build.
 * @param revisionId The identifier for the Phabricator revision that was build.
 * @param buildUrl A link back to the build on Jenkins.
 * @param buildTag Identifies build.
 */
@main
def reportSuccess(
  diffId: String,
  phId: String,
  revisionId: String,
  buildUrl: String,
  buildTag: String): Unit = {

  // Construct message
  val marathonPackage: Path = ((ls! pwd / 'target / 'universal) |? (_.ext == "tgz")).head
  val marathonPackageChecksum = read! pwd / 'target / 'universal / s"${marathonPackage.last}.sha1"

  val msg = s"""
    |(NOTE)\u2714 Build of $diffId completed [[ $buildUrl | $buildTag ]].
    |
    | You can create a DC/OS with your patched Marathon by creating a new pull
    | request with the following changes in [[ https://github.com/dcos/dcos/blob/master/packages/marathon/buildinfo.json | buildinfo.json ]]:
    |
    |   lang=json
    |   "url": "https://downloads.mesosphere.io/marathon/snapshots/${marathonPackage.last}",
    |   "sha1": "${marathonPackageChecksum}"
    |
    |= ＼\\ ٩( ᐛ )و /／ =
    |""".stripMargin

  // We accept and comment in two different calls because Phabriactor won't
  // apply the comment if the diff is already accepted.
  accept(revisionId)
  comment(revisionId, msg)
  reportTestResults(phId, "pass")
}

/**
 * Report failue of diff build back to Phabricator.
 * @param diffId The differential ID of the build.
 * @param phId PHID passed by Harbormaster to build.
 * @param revisionId The identifier for the Phabricator revision that was build.
 * @param buildUrl A link back to the build on Jenkins.
 * @param msg The error message for the failure.
 * @param buildTag Identifies build.
 */
@main
def reportFailure(
  diffId: String,
  phId: String,
  revisionId: String,
  buildUrl: String,
  buildTag: String,
  msg: String) : Unit = {

  // We reject and comment in two different calls because Phabriactor won't
  // apply the comment if the diff is already rejected.
  reject(revisionId)
  comment(revisionId, s"(IMPORTANT)\u2717 Build of $diffId failed [[ $buildUrl | $buildTag ]].\n\nError message: \n>$msg\n= (๑′°︿°๑) =")
  reportTestResults(phId, "fail")
}
