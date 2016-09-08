package mesosphere.marathon.upgrade

import java.net.URL

import akka.actor.Status.Failure
import akka.actor.{ Actor, Props }
import akka.pattern.pipe
import mesosphere.marathon.ResolveArtifactsCanceledException
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.io.{ CancelableDownload, PathFun }
import mesosphere.util.Logging

import scala.concurrent.Promise

private[this] class ResolveArtifactsActor(
  url2Path: Map[URL, String],
  promise: Promise[Boolean],
  storage: StorageProvider)
    extends Actor
    with PathFun
    with Logging {

  import mesosphere.marathon.upgrade.ResolveArtifactsActor.DownloadFinished

  // all downloads that have to be performed by this actor
  var downloads = url2Path.map { case (url, path) => new CancelableDownload(url, storage, path) }

  override def preStart(): Unit = {
    import context.dispatcher
    downloads.map(_.get.map(DownloadFinished) pipeTo self)
    if (url2Path.isEmpty) promise.success(true) // handle empty list
  }

  override def postStop(): Unit = {
    downloads.foreach(_.cancel()) // clean up not finished artifacts
    if (!promise.isCompleted)
      promise.tryFailure(new ResolveArtifactsCanceledException("Artifact Resolving has been cancelled"))
  }

  override def receive: Receive = {
    case DownloadFinished(download) =>
      downloads = downloads.filter(_ != download)
      if (downloads.isEmpty) promise.success(true)
    case Failure(ex) =>
      log.warn("Can not resolve artifact", ex) // do not fail the promise!
  }
}

object ResolveArtifactsActor {
  def props(
    url2Path: Map[URL, String],
    promise: Promise[Boolean],
    storage: StorageProvider): Props = Props(new ResolveArtifactsActor(url2Path, promise, storage))

  case class DownloadFinished(download: CancelableDownload)
}
