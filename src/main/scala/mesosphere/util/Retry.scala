package mesosphere.util

import java.util.logging.{Logger, Level}

/**
 * @author Florian Leibert
 */

object Retry {

  val log = Logger.getLogger(getClass.getName)

  /**
   * Retries a function
   * @param max the maximum retries
   * @param attempt the current attempt number
   * @param i the input
   * @param fnc the function to wrap
   * @tparam I the input parameter type
   * @tparam O the output parameter type
   * @return either Some(instanceOf[O]) or None if more exceptions occurred than permitted by max.
   */
  def retry[I, O](max: Int, attempt: Int, i: I, fnc: (I) => O): Option[O] = {
    try {
      Some(fnc(i))
    } catch {
      case t: Throwable => if (attempt < max) {
        log.log(Level.WARNING, "Retrying attempt:" + attempt, t)
        retry(max, attempt + 1, i, fnc)
      } else {
        log.severe("Giving up after attempts:" + attempt)
        None
      }
    }
  }

}