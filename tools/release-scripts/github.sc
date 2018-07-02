import $ivy.`com.softwaremill.sttp::core:1.1.12`
import $ivy.`com.typesafe.play::play-json:2.6.9`
import com.softwaremill.sttp._
import play.api.libs.json._
import $file.context



import scala.concurrent._


class GithubClient(token: String)(implicit buildContext: context.BuildContext) {

  private implicit val backend = HttpURLConnectionBackend()

  private val ghBaseUri = uri"https://api.github.com/"

  private val marathonOwner = buildContext.marathon.repository.owner

  private val marathonRepo = buildContext.marathon.repository.name

  private def sendPost(path: String, body: String) = {
    sttp.post(ghBaseUri.path(path))
      .header("Authorization", s"token $token")
      .body(body)
      .send()
  }

  private def sendGet(path: String) = {
    sttp.get(ghBaseUri.path(path))
      .header("Authorization", s"token $token")
      .send()
  }


  def createMarathonChangelog15PR(version: String, branchName: String): PullRequestNumber = {
    val path = s"/repos/$marathonOwner/$marathonRepo/pulls"
    val json = JsObject(Seq(
      ("title", JsString(s"Changelog update for $version")),
      ("head", JsString(branchName)),
      ("base", JsString("releases/1.5")),
      ("body", JsString(s"Changelog update for $version")),
      ("maintainer_can_modify", JsBoolean(true))
    ))
    val body = json.toString()
    val response = sendPost(path, body)
    PullRequestNumber(response.header("Location").get.reverse.takeWhile(_ != '/').reverse)
  }

  def waitUntilMerged(pullRequestNumber: PullRequestNumber): Unit = {

    val path = s"/repos/$marathonOwner/$marathonRepo/pulls/${pullRequestNumber.value}/merge"

    var merged = false

    while (!merged) {
      val response = sendGet(path)

      if (response.code == 204) {
        merged = true
      } else {
        Thread.sleep(5000)
      }
    }

  }

  def getMergeCommitSha(pullRequestNumber: PullRequestNumber): Option[String] = {

    val path = s"/repos/$marathonOwner/$marathonRepo/pulls/${pullRequestNumber.value}"

    sendGet(path).body.map { bodyStr =>
      val json = Json.parse(bodyStr)
      val sha = (json \ "merge_commit_sha").get.asInstanceOf[JsString]
      sha.value
    }.toOption

  }

  def createReleaseFromTag(tag: String, changelog: String): Unit = {
    val path = s"/repos/$marathonOwner/$marathonRepo/releases"

    val body = Json.obj(
      "tag_name" -> tag,
      "name" -> tag,
      "body" -> changelog
    )

    sendPost(path, body.toString())
  }

  def requestReviews(prNumber: PullRequestNumber, reviewers: Seq[String]): Unit = {
    val path = s"/repos/$marathonOwner/$marathonRepo/pulls/${prNumber.value}/requested_reviewers"

    val body = Json.obj(
      "reviewers" -> JsArray(reviewers.map(JsString))
    )
  }
}


case class PullRequestNumber(value: String)
