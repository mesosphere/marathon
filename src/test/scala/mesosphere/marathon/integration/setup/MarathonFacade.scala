package mesosphere.marathon.integration.setup

import scala.concurrent.ExecutionContext.Implicits.global
import akka.actor.ActorSystem
import scala.concurrent.duration._
import scala.concurrent.Await.result
import spray.client.pipelining._
import spray.http.HttpResponse
import mesosphere.marathon.api.v2.Group
import mesosphere.marathon.event.http.EventSubscribers
import mesosphere.marathon.event.{Unsubscribe, Subscribe}
import mesosphere.marathon.api.v1.AppDefinition
import java.util.Date

/**
 * GET /apps will deliver something like Apps instead of List[App]
 * Needed for dumb jackson.
 */
case class ListAppsResult(apps:Seq[AppDefinition])
case class ListTasks(tasks:Seq[ITEnrichedTask])
case class ITEnrichedTask(appId:String, id:String, host:String, ports:Seq[Integer], startedAt:Date, stagedAt:Date, version:String)
/**
 * The MarathonFacade offers the REST API of a remote marathon instance
 * with all local domain objects.
 * @param url the url of the remote marathon instance
 */
class MarathonFacade(url:String, waitTime:Duration=30.seconds) extends JacksonSprayMarshaller {

  implicit val system = ActorSystem()
  implicit val appDefMarshaller = marshaller[AppDefinition]
  implicit val groupMarshaller = marshaller[Group]

  //app resource ----------------------------------------------

  def listApps: RestResult[List[AppDefinition]] = {
    val pipeline = sendReceive ~> read[ListAppsResult]
    val res = result(pipeline(Get(s"$url/v2/apps")), waitTime)
    RestResult(res.value.apps.toList, res.code)
  }

  def app(id:String): RestResult[AppDefinition] = {
    val pipeline = sendReceive ~> read[AppDefinition]
    result(pipeline(Get(s"$url/v2/apps/$id")), waitTime)
  }

  def createApp(app:AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id:String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/apps/$id")), waitTime)
  }

  def updateApp(app:AppDefinition): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Put(s"$url/v2/apps/${app.id}", app)), waitTime)
  }

  //apps tasks resource --------------------------------------

  def tasks(appId:String): RestResult[List[ITEnrichedTask]] = {
    val pipeline = sendReceive ~> read[ListTasks]
    val res = result(pipeline(Get(s"$url/v2/apps/$appId/tasks")), waitTime)
    RestResult(res.value.tasks.toList, res.code)
  }

  //group resource -------------------------------------------

  def listGroups: RestResult[List[Group]] = {
    val pipeline = sendReceive ~> read[Array[Group]] ~> toList[Group]
    result(pipeline(Get(s"$url/v2/groups")), waitTime)
  }

  def group(id:String): RestResult[Group] = {
    val pipeline = sendReceive ~> read[Group]
    result(pipeline(Get(s"$url/v2/groups/$id")), waitTime)
  }

  def createGroup(group:Group): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id:String): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Delete(s"$url/v2/groups/$id")), waitTime)
  }

  def updateGroup(group:Group): RestResult[HttpResponse] = {
    val pipeline = sendReceive ~> responseResult
    result(pipeline(Put(s"$url/v2/groups/${group.id}", group)), waitTime)
  }

  //event resource ---------------------------------------------

  def listSubscribers: RestResult[EventSubscribers] = {
    val pipeline = sendReceive ~> read[EventSubscribers]
    result(pipeline(Get(s"$url/v2/eventSubscriptions")), waitTime)
  }

  def subscribe(callbackUrl:String): RestResult[Subscribe] = {
    val pipeline = sendReceive ~> read[Subscribe]
    result(pipeline(Post(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  def unsubscribe(callbackUrl:String): RestResult[Unsubscribe] = {
    val pipeline = sendReceive ~> read[Unsubscribe]
    result(pipeline(Delete(s"$url/v2/eventSubscriptions?callbackUrl=$callbackUrl")), waitTime)
  }

  //convenience functions ---------------------------------------

  /**
   * Delete all existing apps, groups and event subscriptions.
   * @param maxWait the maximal wait time for the cleaning process.
   */
  def cleanUp(withSubscribers:Boolean=false, maxWait:FiniteDuration=30.seconds) = {
    val deadline = maxWait.fromNow
    listGroups.value.map(_.id).foreach(deleteGroup)
    listApps.value.map(_.id).foreach(deleteApp)
    if (withSubscribers) listSubscribers.value.urls.foreach(unsubscribe)
    def waitForReady():Unit = {
      if (deadline.isOverdue()) {
        throw new IllegalStateException(s"Can not clean marathon to default state after $maxWait. Give up.")
      } else {
        val ready = listGroups.value.isEmpty && listApps.value.isEmpty && (listSubscribers.value.urls.isEmpty || !withSubscribers)
        if (!ready && !deadline.isOverdue()) {
          Thread.sleep(100)
          waitForReady()
        }
      }
    }
    waitForReady()
  }
}
