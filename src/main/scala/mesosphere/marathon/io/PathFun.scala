package mesosphere.marathon.io

import java.math.BigInteger
import java.net.{ URLConnection, HttpURLConnection, URL }
import java.security.MessageDigest
import scala.concurrent.Future
import mesosphere.marathon.stream._

import org.apache.commons.io.FilenameUtils.getName

import scala.concurrent.ExecutionContext.Implicits.global

trait PathFun {

  private[this] def md = MessageDigest.getInstance("SHA-1")

  def mdHex(in: String): String = {
    val ret = md
    ret.update(in.getBytes("UTF-8"), 0, in.length)
    new BigInteger(1, ret.digest()).toString(16)
  }

  def fileName(url: URL): String = getName(url.getFile)

  def contentPath(url: URL): Future[String] = contentHeader(url).map { header =>
    //filter only strong eTags and make sure, it can be used as path
    val eTag: Option[String] = header.get("ETag")
      .flatMap(_.filterNot(_.startsWith("W/")).headOption)
      .map(_.replaceAll("[^A-z0-9\\-]", ""))
    val contentPart = eTag.getOrElse(IO.mdSum(url.openStream()))
    s"$contentPart/${fileName(url)}"
  }

  def contentHeader(url: URL): Future[Map[String, Seq[String]]] = Future {
    val connection = url.openConnection() match {
      case http: HttpURLConnection =>
        http.setRequestMethod("HEAD")
        http
      case other: URLConnection => other
    }
    scala.concurrent.blocking(connection.getHeaderFields)
      .map { case (key, list) => (key, list.toSeq) }(collection.breakOut)
  }

}

