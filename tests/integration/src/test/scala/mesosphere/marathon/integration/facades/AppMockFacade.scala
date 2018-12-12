package mesosphere.marathon
package integration.facades

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get}
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging

import scala.async.Async.{async, await}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case class AppMockFacade(host: String, port: Int) extends StrictLogging {

  def suicide()(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer): Future[Done] = async {
    logger.info(s"Send kill request to http://$host:$port/suicide")

    val url = Uri.from(scheme = "http", host = host, port = port, path = "/suicide")
    val result = await(Http().singleRequest(Delete(url)))
    result.discardEntityBytes() // forget about the body
    assert(result.status.isSuccess(), s"App suicide failed with status ${result.status}")
    Done
  }

  def ping()(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer): Future[Done] = async { await(get("/ping")); Done }

  def get(path: String)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer): Future[String] = async {
    logger.info(s"Querying data from http://$host:$port$path")

    val url = Uri.from(scheme = "http", host = host, port = port, path = path)
    val result = await(Http().singleRequest(Get(url)))
    assert(result.status.isSuccess(), s"App data retrieval failed with status ${result.status}")

    await(result.entity.toStrict(30.seconds)).data.decodeString("utf-8")
  }
}

object AppMockFacade extends StrictLogging {

  def apply(task: ITEnrichedTask): AppMockFacade = {
    val host = task.host
    val ports = task.ports.head
    val port = ports.head

    new AppMockFacade(host, port)
  }

  def suicideAll(tasks: List[ITEnrichedTask])(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer): Unit = {
    logger.info(s"Sending suicide requests to the tasks of the ${tasks.head.appId}: ${tasks.map(_.id)}")
    tasks.foreach(AppMockFacade(_).suicide())
  }
}
