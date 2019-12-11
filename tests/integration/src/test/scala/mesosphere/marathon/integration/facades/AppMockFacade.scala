package mesosphere.marathon
package integration.facades

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Post}
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.Materializer
import com.mesosphere.utils.http.{AkkaHttpResponse, RestResult}
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

  def ping()(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, waitTime: FiniteDuration = 30.seconds): Future[RestResult[HttpResponse]] = async { await(get("/ping")) }

  def get(path: String)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, waitTime: FiniteDuration = 30.seconds): Future[RestResult[HttpResponse]] = async {
    logger.info(s"Querying data from http://$host:$port$path")

    val url = Uri.from(scheme = "http", host = host, port = port, path = path)

    val result = await(AkkaHttpResponse.request(Get(url)))
    assert(result.success, s"App data retrieval failed with status ${result.code}")
    result
  }

  def post(path: String)(implicit ec: ExecutionContext, system: ActorSystem, mat: Materializer, waitTime: FiniteDuration = 30.seconds): Future[RestResult[HttpResponse]] = async {
    logger.info(s"Querying data from http://$host:$port$path")

    val url = Uri.from(scheme = "http", host = host, port = port, path = path)

    val result = await(AkkaHttpResponse.request(Post(url)))
    assert(result.success, s"App data retrieval failed with status ${result.code}")
    result
  }
  //  def custom(uri: String, method: RequestBuilding.RequestBuilder = Get)(host: String, port: Int): Future[RestResult[HttpResponse]] = {
  //    val url = s"$scheme://$host:$port$uri"
  //    Retry(s"query: $url", Int.MaxValue, maxDuration = waitTime) {
  //      request(method(url))
  //    }
  //  }
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
