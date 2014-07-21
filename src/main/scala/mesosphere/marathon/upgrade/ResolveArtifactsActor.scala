package mesosphere.marathon.upgrade

import java.net.URL
import scala.concurrent.{ Future, Promise }

import akka.actor.Actor
import akka.actor.Status.Failure
import akka.pattern.pipe

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.io.storage.{ StorageItem, StorageProvider }
import mesosphere.marathon.io.{ IO, PathFun }
import mesosphere.util.{ Logging, ThreadPoolContext }

class ResolveArtifactsActor(app: AppDefinition, urls: Seq[String], promise: Promise[Boolean], storage: StorageProvider) extends Actor with IO with PathFun with Logging {

  import ResolveArtifactsActor.ArtifactResolved

  implicit val threadContext = ThreadPoolContext.context

  override def preStart(): Unit = {
    //TODO: should be cancelable
    Future.sequence(urls.map(persistInArtifactStore)).map(ArtifactResolved) pipeTo self
  }

  override def postStop(): Unit = {
    //TODO: clean up not finished artifacts
  }

  def persistInArtifactStore(url: String): Future[StorageItem] = Future {
    val item = storage.item(uniquePath(url))
    using(new URL(url).openStream()) { item.store }
    item
  }

  override def receive = {
    case ArtifactResolved(_) => promise.success(true)
    case Failure(ex)         => log.warn(s"Can not resolve artifact: $ex") //do not fail the promise!
  }
}

object ResolveArtifactsActor {
  case class ArtifactResolved(urls: Seq[StorageItem])
}
