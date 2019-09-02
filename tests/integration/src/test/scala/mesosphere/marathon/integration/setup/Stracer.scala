package mesosphere.marathon
package integration.setup

import com.typesafe.scalalogging.StrictLogging

import scala.util.Try
import sys.process._

/**
  * A useful set of methods around `strace` utility, used to debug sudden task/executor deaths. Usually, it is hard
  * to find out Mesos task PID since neither Mesos nor Marathon expose it though their APIs. However, give an unique
  * command launch string (e.g. `sleep %random_big_number`) one can grep `ps` output for it.
  */
object Stracer extends StrictLogging {

  /**
    * Return Mesos executor PID for a task with the given command line search string
    */
  def mesosExecutorPid(taskCmd: String): Option[Int] = {
    val taskPID = findPid(searchStr = taskCmd)
    if (taskPID.size != 1) {
      logger.warn(s"Found too many(not enough) processes: $taskPID for search string $taskCmd")
      return None
    }
    parentPid(taskPID.head)
  }

  /**
    * Find parent PID of a give child PID.
    *
    * @return child PID or None if none found
    */
  def parentPid(childPid: Int): Option[Int] = {
    val out = Seq("ps", "-o", "ppid=", "-p", s"$childPid").!!.trim
    logger.info(s"ps -o ppid= -p $childPid\n$out")
    Try(out.trim.toInt).toOption
  }

  /**
    * Filters `ps` output for a given search string and returns a list of PIDs
    *
    * @return a list of processes that contain the search string
    */
  def findPid(searchStr: String): Seq[Int] = {
    logger.info("ps auxww")
    Seq("ps", "auxww").!!.trim
      .split("\n")
      .map{ s => logger.info(s); s }
      .map(_.trim)
      .filter(_.contains(searchStr))
      .map(s => s.split(" +")(1))
      .map(p => Try(p.toInt).toOption)
      .flatten
      .to[Seq]
  }

  /**
    * Run an `strace` on the given PID. Output file name can be provided. Process will continue to run in the
    * background and prevent JVM from exiting. If necessary it can be terminated calling the [[Process.destroy()]]
    * method and [[Process.exitValue()]] to wait for it to exit.
    */
  def stracePid(pid: Int, outputFileName: Option[String] = None): Process = {
    val out = outputFileName.getOrElse(s"$pid-strace.log")
    logger.info(s"sudo strace -p $pid -f -bexecve -o $out")
    Process(s"sudo strace -p $pid -f -bexecve -o $out").run()
  }

  def main(args: Array[String]): Unit = {
  }
}
