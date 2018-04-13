import $ivy.`com.softwaremill.sttp::core:1.1.12`
import $ivy.`com.typesafe.play::play-json:2.6.9`
import com.softwaremill.sttp._
import $file.context
import play.api.libs.json._


class JenkinsClient(username: String, token: String) {

  private implicit val backend = HttpURLConnectionBackend()


  def start15ReleasePipeline(sha: String, version: String): QueueItemId = {

    val jobUri = uri"https://jenkins.mesosphere.com/service/jenkins/view/Marathon/job/marathon-team-releases/job/marathon-community-release-1.5/buildWithParameters"

    val formData = Map(
      "ref" -> sha,
      "version" -> version,
      "runTests" -> "on"
    )

    val response = sttp
      .post(jobUri).body(formData)
      .auth.basic(username, token)
      .send()

    QueueItemId(response.header("Location").get.reverse.tail.takeWhile(_ != '/').reverse)
  }

//  def getBuildId(queueItemId: QueueItemId): BuildId = {}


  def waitForTheJenkinsBuild(buildId: BuildId): Unit = {

    //todo: impplment it

    val uri = uri""


    def isGreen(): Boolean = {
      true
    }

    var green = false

    while (!green) {
      if (isGreen()) {
        green = true
      } else {
        Thread.sleep(5000)
      }
    }

  }

  def getShaFromBuild(buildId: BuildId) = {

    //todo: parse output and get the artifact url and sha

    val consoleOutputUri = uri"https://jenkins.mesosphere.com/service/jenkins/view/Marathon/job/marathon-team-releases/job/marathon-community-release-1.5/${buildId.id}/logText/progressiveText?start=0"

  }
}

case class QueueItemId(id: String)

case class BuildId(id: String)