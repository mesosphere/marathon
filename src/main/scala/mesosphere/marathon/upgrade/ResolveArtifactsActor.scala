package mesosphere.marathon.upgrade

import java.net.URL
import scala.concurrent.Promise

import akka.actor.Actor
import akka.actor.Status.Failure
import akka.pattern.pipe

import mesosphere.marathon.ResolveArtifactsCanceledException
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.io.{ CancelableDownload, IO, PathFun }
import mesosphere.util.Logging

class ResolveArtifactsActor(app: AppDefinition, urls: Seq[String], promise: Promise[Boolean], storage: StorageProvider) extends Actor with IO with PathFun with Logging {

  import mesosphere.marathon.upgrade.ResolveArtifactsActor.DownloadFinished
  import mesosphere.util.ThreadPoolContext.{ context => executionContext }

  //all downloads that have to be performed by this actor
  var downloads = urls.map { url => new CancelableDownload(new URL(url), storage, uniquePath(url)) }

  override def preStart(): Unit = {
    downloads.map(_.get.map(DownloadFinished) pipeTo self)
    if (urls.isEmpty) promise.success(true) //handle empty list
  }

  override def postStop(): Unit = {
    downloads.foreach(_.cancel()) //clean up not finished artifacts
    if (!promise.isCompleted) promise.tryFailure(new ResolveArtifactsCanceledException("Artifact Resolving has been cancelled"))
  }

  override def receive = {
    case DownloadFinished(download) =>
      downloads = downloads.filter(_ != download)
      if (downloads.isEmpty) promise.success(true)
    case Failure(ex) =>
      log.warn("Can not resolve artifact", ex) //do not fail the promise!
  }
}

object ResolveArtifactsActor {
  case class DownloadFinished(download: CancelableDownload)
}
