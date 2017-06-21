package mesosphere.maven

import sbt._

import scala.util.Try
import scala.util.control.NonFatal

object MavenSettings {
  private[this] def loadM2Settings(): Option[xml.Elem] = {
    sys.env.get("DOT_M2_SETTINGS").flatMap { m2SettingsPath =>
      val m2SettingsFile = new java.io.File(m2SettingsPath)
      Try(xml.XML.loadString(IO.read(m2SettingsFile))).toOption
    }
  }

  private[this] def extractM2Servers(m2Settings: xml.Elem): Map[String, (String, String)] = {
    val serverNodes = m2Settings \ "servers" \ "server"
    serverNodes.map { sn =>
      val id = (sn \ "id").text
      val username = (sn \ "username").text
      val password = (sn \ "password").text
      id -> (username, password)
    }.toMap
  }

  private[this] def extractM2Mirrors(m2Settings: xml.Elem): Map[String, String] = {
    val mirrorNodes = m2Settings \ "mirrors" \ "mirror"
    mirrorNodes.map { mn =>
      val id = (mn \ "id").text
      val url = (mn \ "url").text
      id -> url
    }.toMap
  }

  def loadM2Credentials(log: Logger): Seq[Credentials] = try {
    loadM2Settings().map { m2Settings =>
      val servers = extractM2Servers(m2Settings)
      val mirrors = extractM2Mirrors(m2Settings)
      servers.map { case (id, (username, password)) =>
        val url = mirrors.getOrElse(id, "")
        val host = new java.net.URL(url).getHost
        // The realm is hardcoded here because there is no way to provide it
        // in DOT_M2_SETTINGS file, and sbt demands one for HTTP basic authentication.
        Credentials("Sonatype Nexus Repository Manager", host, username, password)
      }
    }.toSeq.flatten
  } catch {
    case NonFatal(ex) =>
      log.error(s"Failed to load credentials from DOT_M2_SETTINGS: $ex")
      Seq()
  }

  def loadM2Resolvers(log: Logger): Seq[Resolver] = try {
    loadM2Settings().map { m2Settings =>
      val mirrors = extractM2Mirrors(m2Settings)
      mirrors.map { case (id, url) => id at url }
    }.toSeq.flatten
  } catch {
    case NonFatal(ex) =>
      log.error(s"Failed to load resolvers from DOT_M2_SETTINGS: $ex")
      Seq()
  }
}
