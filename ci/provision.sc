#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scala.util.control.NonFatal

/**
 * Finds the version of the Mesos Debian package in "project/Dependencies.scala"
 * and installs it.
 */
@main
def installMesos(): Unit = {
  // Find Mesos version
  val versionPattern = """.*MesosDebian = "(.*)"""".r
  val maybeVersion =
      read.lines(pwd/'project/"Dependencies.scala")
          .collectFirst { case versionPattern(v) => v }

  // Install Mesos
  def install_mesos(version: String): Unit = {
    %%('sudo, "apt-get", "install", "-y", "--force-yes", "--no-install-recommends", s"mesos=$version")
  }

  // Stop Mesos service
  def stop_mesos(): Unit = {
    %%("sudo", "systemctl", "stop", "mesos-master.service", "mesos-slave.service", "mesos_executor.slice")
  }

  maybeVersion match {
    case Some(version) =>
      try { install_mesos(version) }
      catch {
        case NonFatal(_) =>
          // Let's try again after updating
          %('sudo, "apt-get", "update")
          install_mesos(version)
      }
      println(s"Successfully installed Mesos $version!")
      stop_mesos()
    case None => throw new IllegalStateException("Could not determine Mesos version.")
  }
}

/**
  * Returns true if passed process name is in the white list.
  *
  * @param proc process name
  * @return true if white listed
  */
def protectedProcess(proc: String): Boolean =
  Vector("slave.jar", "grep", "amm", "ci/pipeline").exists(proc.contains)

/**
  * Returns true if passed process name might be a leak
  * @param proc process name
  * @return true if a possible leak
  */
def eligibleProcess(proc: String): Boolean =
  Vector("app_mock", "mesos", "java").exists(proc.contains)

/**
  * @return list of leaked process names.
  */
def leakedProcesses() = %%('ps, 'aux).out.lines.filter { proc =>
  eligibleProcess(proc) && !protectedProcess(proc)
}

/**
 * Kill stale processes from previous pipeline runs.
 *
 * @param tries Number of tries for killing stale processes.
 */
@main
def killStaleTestProcesses(tries: Int = 30): Unit = {
  val leaks = leakedProcesses()

  if (leaks.isEmpty) {
    println("No leaked processes detected")
  } else {
    println("This requires root permissions. If you run this on a workstation it'll kill more than you expect.\n")
    println(s"Will kill:")
    leaks.foreach( p => println(s"  $p"))

    val pidPattern = """([^\s]+)\s+([^\s]+)\s+.*""".r

    val pids = leaks.map {
      case pidPattern(_, pid) => pid
    }

    // We use %% to avoid exceptions. It is not important if the kill fails.
    println(s"Running 'sudo kill -9 ${pids.mkString(" ")}")
    try { %%('sudo, 'kill, "-9", pids) }
    catch { case e => println(s"Could not kill stale process.") }

    // Print stale processes if any exist to see what couldn't be killed:
    val undead = leakedProcesses()
    if (undead.nonEmpty) {
      println("Couldn't kill some leaked processes:")
      undead.foreach( p => println(s"  $p"))

      if (tries > 0) {
        println("Retrying in 1 second...")
        Thread.sleep(1000)
        killStaleTestProcesses(tries - 1)
      } else {
        println("Giving up.")
        %('echo, "w", ">", "/proc/sysrq-trigger")
        %('dmesg)
      }
    }
  }
}
