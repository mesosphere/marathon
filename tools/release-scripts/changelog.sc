import java.io.StringWriter

import $file.resolvers
import $file.ui
import $ivy.`de.zalando::beard:0.2.0`
import ammonite.ops._
import $file.context




def generateChangelog()(implicit buildContext: context.BuildContext): String = {

  implicit val path = buildContext.marathon.directory.path

  val latestReleasedVerison = {
    val lastReleasedTag = %%('git, "tag", "-l", "--sort=version:refname").out.lines.filter(_.contains("v1.5")).last
    SemVer(lastReleasedTag)
  }

  val nextMarathonVersion = latestReleasedVerison.nextBuildVersion.toReleaseString()

  val currentReleaseVersion = latestReleasedVerison.toTagString()

  val gitLogOutput = %%('git, 'log, s"$currentReleaseVersion..releases/1.5").out.lines.filterNot(_.startsWith("Merge:"))

  def groupByCommits(grouped: Vector[Vector[String]], toProcess: List[String]): Vector[Vector[String]] = {
    toProcess match {
      case Nil => grouped
      case firstLine :: other =>
        val (commitMessage, rest) = other.span(line => !line.startsWith("commit"))
        val commit = firstLine +: commitMessage.toVector
        groupByCommits(grouped :+ commit, rest)
    }
  }




  val commits = groupByCommits(Vector.empty, gitLogOutput.toList).map(Commit)


  val markedCommits = ui.showSpinner("Please select commits to be mentioned in the changelog") {
    ui.showCheckboxWindow(commits.map(c => s"${c.title} (EE: ${c.jiraIssue.map(_.isEE).getOrElse(false)})" -> c))
  }

  val commitsToInclude = markedCommits.filter(_._2).map(_._1)

  import de.zalando.beard.renderer._

  val loader = new FileTemplateLoader(pwd.toString(), ".beard")

  val templateCompiler = new CustomizableTemplateCompiler(templateLoader = loader)

  val template = templateCompiler.compile(TemplateName("changelog_template")).get

  val renderingContext: Map[String, Any] = Map(
    "from" -> currentReleaseVersion,
    "to" -> nextMarathonVersion,
    "commits" -> commitsToInclude.map { commit =>
      Map(
        "hideJiraLink" -> commit.jiraIssue.map(_.isEE).getOrElse(true),
        "title" -> commit.title.trim,
        "jiraIssue" -> commit.jiraIssue.map(_.id).getOrElse("")
      )
    })

  val renderer = new BeardTemplateRenderer(templateCompiler)
  val result: StringWriter = renderer.render(template,
    StringWriterRenderResult(),
    renderingContext
  )
  result.toString
}


case class SemVer(major: Int, minor: Int, build: Int, commit: String) {
  override def toString(): String = s"$major.$minor.$build-$commit"
  def toTagString(): String = s"v$major.$minor.$build"

  /**
    * Release bucket keys are not prefixed.
    */
  def toReleaseString(): String = s"$major.$minor.$build"

  def nextBuildVersion = SemVer(major, minor, build+1, commit)
}

object SemVer {
  val empty = SemVer(0, 0, 0, "")

  // Matches e.g. 1.7.42
  val versionPattern = """^(\d+)\.(\d+)\.(\d+)$""".r
  /**
    * Create SemVer from string which has the form if 1.7.42 and the commit.
    */
  def apply(version: String, commit: String): SemVer =
    version match {
      case versionPattern(major, minor, build) =>
        SemVer(major.toInt, minor.toInt, build.toInt, commit)
      case _ =>
        throw new IllegalArgumentException(s"Could not parse version $version.")
    }

  // Matches e.g. 1.7.42-deadbeef
  val versionCommitPattern = """^(\d+)\.(\d+)\.(\d+)-(\w+)$""".r

  // Matches e.g. v1.7.42
  val versionTagPattern = """^v?(\d+)\.(\d+)\.(\d+)$""".r

  /**
    * Create SemVer from string which has the form if 1.7.42-deadbeef.
    */
  def apply(version: String): SemVer =
    version match {
      case versionCommitPattern(major, minor, build, commit) =>
        SemVer(major.toInt, minor.toInt, build.toInt, commit)
      case versionTagPattern(major, minor, build) =>
        SemVer(major.toInt, minor.toInt, build.toInt, "")
      case _ =>
        throw new IllegalArgumentException(s"Could not parse version $version.")
    }

}

trait JiraIssue {
  def id: String
  def isEE: Boolean
}
case class MarathonIssue(id: String) extends JiraIssue {
  override def isEE: Boolean = false
}
case class MarathonEeIssue(id: String) extends JiraIssue {
  override def isEE = true
}

case class Commit(raw: Vector[String]) {
  val marathonIssueRegex = """(MARATHON-\d+)""".r
  val marathonEeIssueRegex = """(MARATHON_EE-\d+)""".r

  private def findIssueNumber(text: String): Option[JiraIssue] = {
    marathonIssueRegex.findFirstIn(text).map(MarathonIssue).orElse {
      marathonEeIssueRegex.findFirstIn(text).map(MarathonEeIssue)
    }
  }

  def sha = raw(0).split(" ")(1)
  def title = raw.filter(_.nonEmpty)(3)
  def jiraIssue: Option[JiraIssue] = {
    raw.find { line =>
      val lowercased = line.toLowerCase()
      lowercased.contains("jira") && (lowercased.contains("marathon-") || lowercased.contains("marathon_ee-"))
    }.flatMap { line =>
      findIssueNumber(line)
    }.orElse {
      //someone forgot to use the word Jira in the description
      findIssueNumber(raw.mkString(" "))
    }
  }
}