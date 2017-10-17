package mesosphere.marathon
package api.akkahttp

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import ch.qos.logback.classic.{ Level, Logger, LoggerContext }
import com.typesafe.config.{ Config, ConfigRenderOptions }
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.election.ElectionService
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.plugin.auth.AuthorizedResource.{ SystemConfig, SystemMetrics }
import mesosphere.marathon.plugin.auth.{ Authenticator, Authorizer, UpdateResource, ViewResource }
import mesosphere.marathon.raml.LoggerChange
import org.slf4j.LoggerFactory
import stream.Implicits._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * The SystemController handles system level functionality like configuration, metrics and logging.
  */
class SystemController(cfg: Config)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller with StrictLogging {

  import Directives._
  import EntityMarshallers._

  /**
    * GET /ping
    * @return a simple pong as text/plain
    */
  def ping: Route = complete("pong")

  /**
    * GET /metrics
    * @return a snapshot of all system level metrics.
    */
  def metrics: Route = {
    authenticated.apply { implicit identity =>
      authorized(ViewResource, SystemMetrics).apply {
        complete(Metrics.snapshot())
      }
    }
  }

  /**
    * GET /config
    * @return the complete application config (typesafe config)
    */
  def config: Route = {
    authenticated.apply { implicit identity =>
      authorized(ViewResource, SystemMetrics).apply {
        complete(cfg.root().render(ConfigRenderOptions.defaults().setJson(true)))
      }
    }
  }

  /**
    * GET /logging
    * @return a map of all loggers with related log level
    */
  def showLoggers: Route = {
    authenticated.apply { implicit identity =>
      authorized(ViewResource, SystemConfig).apply {
        LoggerFactory.getILoggerFactory match {
          case lc: LoggerContext =>
            complete(lc.getLoggerList.map { logger =>
              logger.getName -> Option(logger.getLevel).map(_.levelStr).getOrElse(logger.getEffectiveLevel.levelStr + " (inherited)")
            }.toMap[String, String])
        }
      }
    }
  }

  /**
    * POST /logging
    * @return the log change.
    */
  def changeLoggers: Route = {
    authenticated.apply { implicit identity =>
      authorized(UpdateResource, SystemConfig).apply {
        entity(as[LoggerChange]) { change =>
          LoggerFactory.getILoggerFactory.getLogger(change.logger) match {
            case log: Logger =>
              val level = Level.valueOf(change.level.value.toUpperCase)

              // current level can be null, which means: use the parent level
              // the current level should be preserved, no matter what the effective level is
              val currentLevel = log.getLevel
              val currentEffectiveLevel = log.getEffectiveLevel
              logger.info(s"Set logger ${log.getName} to $level current: $currentEffectiveLevel")
              log.setLevel(level)

              // if a duration is given, we schedule a timer to reset to the current level
              change.durationSeconds.foreach(duration => actorSystem.scheduler.scheduleOnce(duration.seconds, new Runnable {
                override def run(): Unit = {
                  logger.info(s"Duration expired. Reset Logger ${log.getName} back to $currentEffectiveLevel")
                  log.setLevel(currentLevel)
                }
              }))
              complete(change)
          }
        }
      }
    }
  }

  override val route: Route = {
    // To maintain the functionality of the original API, this endpoint is leader aware.
    // It would make sense to allow the functionality of this controller on every instance.
    asLeader(electionService) {
      path("ping") {
        get { ping }
      } ~
        path("metrics") {
          get { metrics }
        } ~
        path("config") {
          get { config }
        } ~
        path("logging") {
          get { showLoggers } ~ post { changeLoggers }
        }
    }
  }
}
