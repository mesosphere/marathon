package mesosphere.marathon
package api.akkahttp
import akka.http.scaladsl.server.{ Directives => AkkaDirectives }

/**
  * All Marathon Directives and Akka Directives
  *
  * These should be imported by the respective controllers
  */
object Directives extends AuthDirectives with LeaderDirectives with AkkaDirectives
