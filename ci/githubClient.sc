#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import $file.awsClient

import scalaj.http._
import upickle._

def execute(path:String, body: String): Unit = {
  val GITHUB_API_TOKEN =
    sys.env.getOrElse("GIT_PASSWORD", throw new IllegalArgumentException("GIT_PASSWORD enviroment variable was not set."))
  val GITHUB_API_USER =
    sys.env.getOrElse("GIT_USER", throw new IllegalArgumentException("GIT_USER enviroment variable was not set."))

  // Execute request
  println(body)
  val response = Http(s"https://api.github.com/$path")
    .auth(GITHUB_API_USER, GITHUB_API_TOKEN)
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .postData(body)
    .asString
    .throwError
}

/**
 * Reject pull request with pullNumber.
 */
def reject(pullNumber: String): Unit = {
  val request = Js.Obj(
    "body" -> Js.Str(""),
    "event" -> Js.Str("REQUEST_CHANGES")
    )
  val path = s"repos/mesosphere/marathon/pulls/$pullNumber/reviews"
  execute(path, request.toString)
}

/**
 * Comment with msg on pull request with pullNumber.
 */
def comment(pullNumber: String, msg: String, event: String = "COMMENT"): Unit = {
  val request = Js.Obj(
    "body"  -> Js.Str(msg),
    "event" -> Js.Str(event)
    )
  val path = s"repos/mesosphere/marathon/pulls/$pullNumber/reviews"
  execute(path, request.toString)
}

/**
 * Report success of diff build back to GitHub.
 *
 * @param pullNumber The pull request of the build.
 * TODO: Add commitId
 * @param buildUrl A link back to the build on Jenkins.
 * @param buildTag Identifies build.
 * @param maybeArtifact A description of the Marathon binary that has been uploaded.
 *    It's None when now package was uploaded.
 */
def reportSuccess(
  pullNumber: String,
  buildUrl: String,
  buildTag: String,
  maybeArtifact: Option[awsClient.Artifact]): Unit = {

  // TODO: Include test result overview in comment.
  //val testResults = reportTestResults(phId, "pass")

  // Collect unsound, i.e. canceled, tests
 // val unsoundTests = testResults.value
 //   .collect { case test: Js.Obj if test("result").value == "unsound" => test  }
 // val hasUnsoundTests = unsoundTests.nonEmpty
 val hasUnsoundTests = false

  // Construct message
  val buildinfoDiff = maybeArtifact.fold(""){ artifact =>
    s"""
      |```json
      |   "url": "${artifact.downloadUrl}",
      |   "sha1": "${artifact.sha1}"
      |```
     """.stripMargin
  }

  var msg = s"""
    |**\u2714 Build of #$pullNumber completed successfully.**
    |
    |See details at [$buildTag]($buildUrl).
    |
    |You can create a DC/OS with your patched Marathon by creating a new pull
    |request with the following changes in [buildinfo.json](https://github.com/dcos/dcos/blob/master/packages/marathon/buildinfo.json):
    |
    |$buildinfoDiff
    |
    |""".stripMargin

  if (!hasUnsoundTests) {
    msg += "**＼\\ ٩( ᐛ )و /／**"
  } //else {
 //   val unsoundTestsList: String = unsoundTests.foldLeft("") { (msg:String, test: Js.Obj) =>
 //     msg + s"""\n- `${test("name").value}`"""
 //   }

 //   msg += s"""
 //   |WARNING: The following tests failed and have been marked as canceled.
 //   |Are you sure you want to land this patch?
 //   | $unsoundTestsList
 //   |
 //   |Anyhow, check the [[ $buildUrl/testReport | skipped tests ]] on Jenkins for details and decide for yourself.
 //   |
 //   |**¯\\_(ツ)_/¯**
 //   |""".stripMargin
 // }

  //accept(revisionId)
  comment(pullNumber, msg, event="APPROVE")
}

@main
def main(): Unit = {
  reportSuccess("5513","https://jenkins.mesosphere.com/service/jenkins/view/Marathon/job/marathon-pipelines/view/change-requests/job/PR-5513/1/", "PR-5513", None)
}
