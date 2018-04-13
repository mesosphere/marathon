import $ivy.`com.softwaremill.sttp::core:1.1.12`
import $ivy.`com.typesafe.play::play-json:2.6.9`


import com.softwaremill.sttp._
import play.api.libs.json._

import ammonite.ops._


case class Credentials(github: String, jenkins: (String, String))

implicit val credentialsFormat = Json.format[Credentials]

val credentialsPath = home / ".marathon-release-credentials-15"
implicit val backend = HttpURLConnectionBackend()

type ErrorMessage = String
type Token = String

def validateGithubCredentials(token: String): Either[ErrorMessage, Token] = {
  val requiredScopes = Set("public_repo", "repo:status", "repo_deployment")
  def findMissingScopes(token: String): Option[Set[String]] = {
    val maybeScopes = {
      val response = sttp.get(uri"https://api.github.com")
        .header("Authorization", s"token $token").send()

      if (response.is200) {
        Some(response.header("X-OAuth-Scopes").get.split(",").map(_.trim).toSet)
      } else {
        None
      }
    }
    maybeScopes map { scopes =>
      requiredScopes diff scopes
    }
  }

  findMissingScopes(token).map { missingScopes =>
    if (missingScopes.nonEmpty) {
      Left(s"Github token is missing the next scopes: ${missingScopes.mkString(", ")}. Please enter the token with the correct scopes")
    } else {
      Right(token)
    }
  }.getOrElse {
    Left("Github token is invalid. Please check your token and try again.")
  }
}

def askForGithubCredentials(): String = {
  println("Please enter your Github token:")
  val ghToken = scala.io.StdIn.readLine()
  validateGithubCredentials(ghToken) match {
    case Right(token) => token
    case Left(errorMessage) =>
      println(errorMessage)
      askForGithubCredentials()
  }
}

def validateJenkinsCredentials(username: String, token: String): Either[ErrorMessage, (String, String)] = {
  val jenkinsTestUri = uri"https://jenkins.mesosphere.com/service/jenkins/view/Marathon/job/marathon-pipelines/job/releases%252F1.5/api/json?pretty=true"
  val response = sttp.get(jenkinsTestUri).auth.basic(username, token).send()
  if (response.is200) {
    Right(username -> token)
  } else {
    Left("Jenkins token is not valid.")
  }

}

def askForJenkinsCredentials(): (String, String) = {
  println("Please enter your Jenkins username:")
  val jenkinsUsername = scala.io.StdIn.readLine()
  println("Please enter your Jenkins token:")
  val jenkinsToken = scala.io.StdIn.readLine()
  validateJenkinsCredentials(jenkinsUsername, jenkinsToken) match {
    case Right((username, token)) => username -> token
    case Left(errorMessage) =>
      println(errorMessage)
      askForJenkinsCredentials()
  }
}

def askForTokens(): Credentials = {
  println("Check the following page to figure out how to generate a token with a `repo` scope: https://help.github.com/articles/creating-a-personal-access-token-for-the-command-line/")
  val ghToken = askForGithubCredentials()
  println("To obtain Jenkins tokens, check the following page for instructions: https://wiki.jenkins.io/display/JENKINS/Authenticating+scripted+clients")
  val jenkinsToken = askForJenkinsCredentials()
  Credentials(ghToken, jenkinsToken)
}

def loadUserCredentials(): Credentials = if (exists(credentialsPath)) {
  val credentialsString = read(credentialsPath)
  val maybeCredentials = Json.parse(credentialsString).asOpt[Credentials]
  maybeCredentials.map { savedCredentials =>
    println("Found saved credentials, validating...")
    (for {
      github <- validateGithubCredentials(savedCredentials.github)
      jenkins <- validateJenkinsCredentials(savedCredentials.jenkins._1, savedCredentials.jenkins._2)
    } yield {
      println("Validation successful!")
      Credentials(github, jenkins)
    }).getOrElse {
      //credentials are not valid, requesting a new one
      println("Stored credentials aren't valid, requesting a new ones.")
      rm(credentialsPath)
      loadUserCredentials()
    }
  }.getOrElse {
    //credentials file is corrupted, requesting a new one
    println(s"No valid file with tokens found in $credentialsPath, requesting new credentials.")
    rm(credentialsPath)
    loadUserCredentials()
  }
} else {
  val credentials = askForTokens()
  val credentialsString = Json.toJson(credentials).toString()
  write(credentialsPath, credentialsString)
  credentials
}