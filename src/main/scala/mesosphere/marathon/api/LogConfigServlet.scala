// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package mesosphere.marathon
package api

import ch.qos.logback.classic.LoggerContext
import com.google.common.collect.Lists
import java.lang.Iterable

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.{ Collections, List }

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import org.slf4j.LoggerFactory

/**
  * Servlet that allows for dynamic adjustment of the http configuration.
  * TODO port to Scala
  *
  * @author William Farner
  */
class LogConfigServlet extends MustacheServlet("logconfig.mustache") {
  private val LOG_LEVELS = Lists.newArrayList(
    Level.OFF.toString(),
    Level.ERROR.toString(),
    Level.WARN.toString(),
    Level.INFO.toString(),
    Level.DEBUG.toString(),
    Level.TRACE.toString(),
    Level.ALL.toString(),
    "INHERIT" // Display value for a null level, the logger inherits from its ancestor.
  )

  override protected def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    displayPage(req, resp, true)
  }

  override protected def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    displayPage(req, resp, false)
  }

  protected def displayPage(req: HttpServletRequest, resp: HttpServletResponse, posted: Boolean): Unit = {

    var configChange: String = null

    if (posted) {
      val loggerName = req.getParameter("logger")
      val loggerLevel = req.getParameter("level")
      if (loggerName != null && loggerLevel != null) {
        val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[ch.qos.logback.classic.Logger]
        val newLevel: Level = if (loggerLevel.equals("INHERIT")) null else Level.toLevel(loggerLevel)
        logger.setLevel(newLevel)
        if (newLevel != null) {
          maybeAdjustHandlerLevels(logger, newLevel)
        }

        configChange = String.format("%s level changed to %s", loggerName, loggerLevel)
      }
    }

    val loggerConfigs = Lists.newArrayList[LoggerConfig]
    val loggers: List[Logger] = (LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]).getLoggerList()

    loggers.forEach { logger =>
      val level: Option[String] = Option(logger.getLevel).map(_.toString)
      loggerConfigs.add(LoggerConfig(logger.getName(), level))
    }
    Collections.sort(loggerConfigs)

    writeTemplate(resp, TemplateContext(loggerConfigs, LOG_LEVELS, configChange))
  }

  private def maybeAdjustHandlerLevels(logger: Logger, newLevel: Level): Unit = {
    if (newLevel.toInt() < logger.getLevel().toInt()) {
      logger.setLevel(newLevel)
    }
  }

  private case class LoggerConfig(name: String, levelOpt: Option[String]) extends Comparable[LoggerConfig] {
    def level = levelOpt.getOrElse("INHERIT")

    override def compareTo(o: LoggerConfig): Int = {
      name.compareTo(o.name)
    }
  }

  private case class TemplateContext(loggers: Iterable[LoggerConfig], levels: Iterable[String], configChange: String)
}
