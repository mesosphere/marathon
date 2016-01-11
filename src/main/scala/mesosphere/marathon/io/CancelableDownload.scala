package mesosphere.marathon.io

import java.net.URL
import java.util.UUID

import mesosphere.marathon.CanceledActionException
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.util.{ Logging, ThreadPoolContext }

import scala.concurrent.Future

/**
  * Download given url to given path of given storage provider.
  * Note: the url stream is persisted in a temporary file in the same location as the path.
  * The temporary file is moved, when the download is complete.
  * @param url the url to download
  * @param provider the storage provider
  * @param path the path inside the storage, to store the content of the url stream.
  */
final class CancelableDownload(val url: URL, val provider: StorageProvider, val path: String) extends Logging {

  val tempItem = provider.item(path + UUID.randomUUID().toString)
  var canceled = false
  def cancel(): Unit = { canceled = true }

  lazy val get: Future[CancelableDownload] = Future {
    log.info(s"Download started from $url to path $path")
    IO.using(url.openStream()) { in =>
      tempItem.store { out => IO.transfer(in, out, close = false, !canceled) }
    }
    if (!canceled) {
      log.info(s"Download finished from $url to path $path")
      tempItem.moveTo(path)
    }
    else {
      log.info(s"Cancel download of $url. Remove temporary storage item $tempItem")
      tempItem.delete()
      throw new CanceledActionException(s"Download of $path from $url has been canceled")
    }
    this
  }(ThreadPoolContext.ioContext)

  override def hashCode(): Int = url.hashCode()
  override def equals(other: Any): Boolean = other match {
    case c: CancelableDownload => (c.url == this.url) && (c.path == path)
    case _                     => false
  }
}

