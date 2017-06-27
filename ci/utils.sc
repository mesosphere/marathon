// A collection of pipeline utilities such as stage names and colors.
import scala.util.control.NonFatal

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

def println(text: String): Unit = println(text)
def println(text: String, color: String): Unit = printWithColor(s"$text\n", color)

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
