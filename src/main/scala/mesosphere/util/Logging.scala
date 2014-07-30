package mesosphere.util

import org.apache.log4j.Logger

trait Logging {
  protected[this] val log = Logger.getLogger(getClass.getName)
}
