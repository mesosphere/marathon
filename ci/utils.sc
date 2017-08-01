// A collection of pipeline utilities such as stage names and colors.

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import $file.provision

// Color definitions
object Colors {
  val BrightRed = "\u001b[31;1m"
  val BrightGreen = "\u001b[32;1m"
  val BrightBlue = "\u001b[34;1m"
  val Reset = "\u001b[0m"
}

def printWithColor(text: String, color: String): Unit = {
  print(color)
  print(text)
  print(Colors.Reset)
}

def printlnWithColor(text: String, color: String): Unit = printWithColor(s"$text\n", color)

def printHr(color: String, character: String = "*", length: Int = 80): Unit = {
  printWithColor(s"${character * length}\n", color)
}

def printStageTitle(name: String): Unit = {
  val indent = (80 - name.length) / 2
  print("\n")
  print(" " * indent)
  printWithColor(s"$name\n", Colors.BrightBlue)
  printHr(Colors.BrightBlue)
}

case class BuildException(val cmd: String, val exitValue: Int, private val cause: Throwable = None.orNull)
  extends Exception(s"'$cmd' exited with $exitValue", cause)
case class StageException(private val message: String = "", private val cause: Throwable = None.orNull)
  extends Exception(message, cause)
def stage[T](name: String)(block: => T): T = {
  printStageTitle(name)

  try {
    block
  }
  catch { case NonFatal(e) =>
    throw new StageException(s"Stage $name failed.", e)
  }
}

/**
 * Run a process with given commands and time out it runs too long.
 *
 * @param timeout The maximum time to wait.
 * @param commands The commands that are executed in a process. E.g. "sbt",
 *  "compile".
 */
def runWithTimeout(timeout: FiniteDuration)(commands: Seq[String]): Unit = {

  val builder = new java.lang.ProcessBuilder()
  val buildProcess = builder
    .directory(new java.io.File(pwd.toString))
    .command(commands.asJava)
    .inheritIO()
    .start()

  try {
    val exited = buildProcess.waitFor(timeout.length, timeout.unit)

    if (exited) {
      val exitValue = buildProcess.exitValue
      if(buildProcess.exitValue != 0) {
        val cmd = commands.mkString(" ")
        throw new utils.BuildException(cmd, exitValue)
      }
    } else {
      // The process timed out. Try to kill it.
      buildProcess.destroyForcibly().waitFor()
      val cmd = commands.mkString(" ")
      throw new java.util.concurrent.TimeoutException(s"'$cmd' timed out after $timeout.")
    }
  } finally {
    // This also cleans forked SBT processes.
    provision.killStaleTestProcesses()
  }
}

/**
 * @return True if build is on master build.
 */
def isMasterBuild(): Boolean = {
  sys.env.get("JOB_NAME").contains("marathon-pipelines/master")
}
