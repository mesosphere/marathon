#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import $file.utils
import $file.awsClient

import scala.util.control.NonFatal
import scalaj.http._
import upickle._

/**
 * Makes a POST request to GitHub's API with path and body.
 * E.g. "repos/mesosphere/marathon/pulls/5513/reviews" would post the body as a
 * comment.
 *
 * @param path The API path. See path in
 *   https://developer.github.com/v3/pulls/reviews/#create-a-pull-request-review
 *   for an example.
 * @param body The body of the post request.
 */
def execute(path:String, body: String): Unit = {
  val GITHUB_API_TOKEN =
    sys.env.getOrElse("GIT_PASSWORD", throw new IllegalArgumentException("GIT_PASSWORD enviroment variable was not set."))
  val GITHUB_API_USER =
    sys.env.getOrElse("GIT_USER", throw new IllegalArgumentException("GIT_USER enviroment variable was not set."))

  val response = Http(s"https://api.github.com/$path")
    .auth(GITHUB_API_USER, GITHUB_API_TOKEN)
    .timeout(connTimeoutMs = 5000, readTimeoutMs = 100000)
    .postData(body)
    .asString
    .throwError
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
 * Reject pull request with pullNumber.
 */
def reject(
  pullNumber: String,
  buildUrl: String,
  buildTag: String): Unit = {
  val msg = s"I'm building your change at [$buildTag]($buildUrl)."

  comment(pullNumber, msg, "REQUEST_CHANGES")
}

/**
 * Collect test results.
 *
 * @return the parsed test results.
 */
@main
def collectTestResults(): Js.Arr = {

  try {
    // Join all results
    val testResults = ls! pwd / 'target / "phabricator-test-reports" |? ( _.ext == "json")
    val joinedTestResults: Js.Arr = testResults.view.map(read!)
      .map(upickle.json.read)
      .collect { case a: Js.Arr => a }
      .reduce { (l: Js.Arr, r: Js.Arr) =>
        val n = l.arr ++ r.arr
        Js.Arr(n :_*)
      }

    joinedTestResults
  } catch {
    case NonFatal(e) =>
      utils.printlnWithColor(s"Could not collect test results: ${e.getMessage}", utils.Colors.BrightRed)
      Js.Arr()
  }
}

/**
 * Report success of diff build back to GitHub.
 *
 * @param pullNumber The pull request of the build.
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

  val testResults = collectTestResults()

  // Collect unsound, i.e. canceled, tests
  val unsoundTests = testResults.value
    .collect { case test: Js.Obj if test("result").value == "unsound" => test  }
  val hasUnsoundTests = unsoundTests.nonEmpty

  // Construct message
  val buildinfoDiff = maybeArtifact.fold(""){ artifact =>
    s"""
      |```json
      |"url": "${artifact.downloadUrl}",
      |"sha1": "${artifact.sha1}"
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
    |You can run system integration test changes of this PR against Marathon
    |master by tirggering [this Jenkins job](https://jenkins.mesosphere.com/service/jenkins/view/Marathon/job/system-integration-tests/job/marathon-si-pr/build?delay=0sec) with the `Pull_Request_id` `$pullNumber`.
    |The job then reports back to this PR.
    |
    |""".stripMargin

  if (!hasUnsoundTests) {
    msg += "**＼\\ ٩( ᐛ )و /／**"
  } else {
    val unsoundTestsList: String = unsoundTests.foldLeft("") { (msg:String, test: Js.Obj) =>
      msg + s"""\n- `${test("name").value}`"""
    }

    msg += s"""
    |The following tests failed and have been marked as canceled. Are you sure you want to land this patch?
    | $unsoundTestsList
    |
    |Anyhow, check the [skipped tests]($buildUrl/testReport) on Jenkins for details and decide for yourself.
    |
    |**¯\\_(ツ)_/¯**
    |""".stripMargin
  }

  comment(pullNumber, msg, event="APPROVE")
}

/**
 * Report failure of build back to GitHub.
 *
 * @param pullNumber The pull request of the build.
 * @param buildUrl A link back to the build on Jenkins.
 * @param buildTag Identifies build.
 * @param msg The error message for the failure.
 */
def reportFailure(
  pullNumber: String,
  buildUrl: String,
  buildTag: String,
  msg: String) : Unit = {

  val body = s"""
    |**\u2717 Build of #$pullNumber failed.**
    |
    |See the [logs]($buildUrl/console) and [test results]($buildUrl/testReport) for details.
    |
    |Error message:
    |>$msg
    |
    |**(๑′°︿°๑)**
    |""".stripMargin

  comment(pullNumber, body, event="REQUEST_CHANGES")
}
