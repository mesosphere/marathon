#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

val stuffToKill = %%('ps, 'aux).out.lines.filter { proc =>
  (proc.contains("app_mock") || proc.contains("mesos") || proc.contains("java")) &&
    !(proc.contains("slave.jar") || proc.contains("grep") || proc.contains("kill_stale_test_processes.sc"))
}



if (stuffToKill.isEmpty) {
  println("No junk processes detected")
} else {
  println("This requires root permissions. If you run this on a workstation it'll kill more than you expect.")
  println()
  println(s"Will kill $stuffToKill")

  val pidPattern = """([^\s]+)\s+([^\s]+)\s+.*""".r

  val pids = stuffToKill.map {
    case pidPattern(_, pid) => pid
  }

  println(s"Running 'sudo kill -9 ${pids.mkString(" ")}")
  %('sudo, 'kill, "-9", pids)
}