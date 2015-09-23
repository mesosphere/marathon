package mesosphere.util

import org.slf4j.LoggerFactory

trait Logging {
  protected[this] val log = LoggerFactory.getLogger(getClass.getName)
}
