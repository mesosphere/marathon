package mesosphere.marathon
package integration.setup

import com.mesosphere.utils.ProcessOutputToLogStream
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try
import sys.process._

/**
  * A class simplifying using `strace` in test methods. See [[Stracer.withStracer()]] fixture method.
  */
class Stracer extends StrictLogging {

  var straced = List.empty[Process]

  /**
    * Runs `strace` for the executor of the task with the passed `taskCmd`. The later has to be unique so that only
    * one process is found.
    */
  def straceExecutor(taskCmd: String): Unit = {
    val epid = Stracer.mesosExecutorPid(taskCmd)
    if (epid.isEmpty) {
      logger.info(s"FAILED to find executor PID for a task with cmd = $taskCmd")
    } else {
      val proc = Stracer.stracePid(epid.head)
      straced = straced :+ proc
    }
  }

  /**
    * Destroys all running `strace` processes. Called automatically when using [[Stracer.withStracer()]] test fixture.
    */
  def cleanup(): Unit = {
    straced.foreach{ p =>
      p.destroy()
      p.exitValue()
    }
    straced = List.empty[Process]
  }
}

/**
  * A useful set of methods around `strace` utility, used to debug sudden task/executor deaths. Usually, it is hard
  * to find out Mesos task PID since neither Mesos nor Marathon expose it in their APIs. However, given an unique
  * command launch string (e.g. `sleep %random_big_number`) one can grep `ps` output for it and attach `strace` to
  * the process. `strace` output is logged with the rest of the test logs.
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

    System.getProperty("os.name") match {
      // On Linux, executor is the grand-parent of the actual process PID
      case "Linux" =>
        parentPid(taskPID.head).flatMap(parentPid(_))
      // On OS X, executor is the direct parent of the actual process PID
      case _ =>
        parentPid(taskPID.head)
    }
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
      .map { s => logger.info(s); s }
      .map(_.trim)
      .filter(_.contains(searchStr))
      .map(s => s.split(" +")(1))
      .flatMap(p => Try(p.toInt).toOption)
      .to(Seq)
  }

  /**
    * Run an `strace` on the given PID. Logger line prefix can be provided. Process will continue to run in the
    * background and prevent JVM from exiting. If necessary it can be terminated calling the [[Process.destroy()]]
    * method and [[Process.exitValue()]] to wait for it to exit.
    */
  def stracePid(pid: Int, output: Option[String] = None): Process = {
    val out = output.getOrElse(s"strace-$pid")
    logger.info(s"sudo strace -p $pid -f -bexecve")
    Process(s"sudo strace -p $pid -f -bexecve").run(ProcessOutputToLogStream(out))
  }

  /**
    * A fixture method, providing an instance [[Stracer]] class to the test. Use it like:
    * ```
    * "some process should not die" in withStracer { st =>
    *    st.straceExecutor(taskCmd = "sleep 1337")
    *    ...
    * }
    * ```
    *
    * `strace` processes will be cleaned up after the test.
    */
  def withStracer(fn: Stracer => Any): Unit = {
    val stracer = new Stracer
    try fn(stracer)
    finally stracer.cleanup()
  }
}
