#!/usr/bin/env amm

import $ivy.`org.scalatest::scalatest:3.0.2`
import org.scalatest._
import org.scalatest.concurrent.Eventually
import scala.concurrent.{ Promise, Await }
import scala.concurrent.duration._
import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scala.util.Try

abstract class UnitTest extends WordSpec with GivenWhenThen with Matchers with Eventually {
  val veryPatient = PatienceConfig(timeout = scaled(60.seconds), interval = scaled(1.second))
  val somewhatPatient = PatienceConfig(timeout = scaled(15.seconds), interval = scaled(250.millis))
}

case class Container(containerId: String, ipAddress: String)

trait FailureWatcher extends Suite {
  private var _resultPromise = Promise[Boolean]
  def result = _resultPromise.future
  override def run(testName: Option[String], args: Args): Status = {
    val status = super.run(testName, args)
    status.whenCompleted { r => _resultPromise.tryComplete(r) }
    status
  }
}

trait MesosTest extends UnitTest with BeforeAndAfterAll with FailureWatcher {
  val packagePath = pwd / up / up / 'tools / 'packager
  val PackageFile = "^marathon_.+\\.([a-z0-9]+)_all.(rpm|deb)$".r // TODO: match rpm packages as well.

  def assertOneOfEachKind(): Unit = {
    assert(packagePath.toIO.exists, "package path ${packagePath} does not exist! Did you build packages?")
    val counts = ls(packagePath).
      map(_.last).
      collect { case PackageFile(os, ext) => (os, ext) }.
      groupBy(identity).
      mapValues(_.length)

    assert(counts.size > 0, "No packages exist!")
    assert(counts.forall { case (k, count) => count == 1 }, "Some packages have multiple versions; " +
      s"please assert that ${packagePath} contains one version of each package only (for each service manager and package format)")
  }

  def removeStaleDockerInstances(): Unit = {
    val images = %%("docker", "ps", "-a", "--filter", "label=marathon-package-test", "--format", "{{.ID}}").
      out.string.split("\n").filter(_ != "")

    images.foreach { id =>
      System.err.println(s"Cleaning up container ${id}")
      %%("docker", "rm", "-f", id)
    }
  }

  def runContainer(args: String*): Container = {
    val containerId = %%.apply(Seq("docker", "run", "-d", "--privileged", "--label", "marathon-package-test",
      "--cap-add", "SYS_ADMIN", "-v", "/sys/fs/cgroup:/sys/fs/cgroup:ro") ++ args).out.string.trim
    val ipAddress = %%("docker", "exec", "-i", containerId, "hostname", "-i").out.string.trim
    Container(containerId = containerId, ipAddress = ipAddress)
  }

  def startMesos(): Container = {
    runContainer("--name", "debian-mesos", "marathon-package-test:mesos")
  }

  def execBashWithoutCapture(containerId: String, cmd: String): Unit = {
    %("docker", "exec", "-i", containerId, "bash", "-c", cmd)
  }

  def execBash(containerId: String, cmd: String): String = {
    %%("docker", "exec", "-i", containerId, "bash", "-c", cmd).out.string
  }

  /** OS X temp directory /var/folders really resides in /private/var/folders and this confuses Docker. Detect and
    * workaround */
  def getTmpFile(): Path = {
    val t = tmp()
    val withPrivate = root / 'private / t.relativeTo(root)
    if (withPrivate.toIO.exists)
      withPrivate
    else
      t
  }

  override def beforeAll(): Unit = {
    try { super.beforeAll() }
    finally {
      assertOneOfEachKind()
      removeStaleDockerInstances()
    }
  }

  override def afterAll(): Unit = {
    try {
      if (!sys.env.contains("SKIP_CLEANUP")) {
        removeStaleDockerInstances()
      }
    } finally super.afterAll()
  }
}

class Debian8Test(marathonDebPackage: String) extends MesosTest {

  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()
    systemd = runContainer("--name", "debian8", "-v", s"${packagePath}:/var/packages", "marathon-package-test:debian8")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(systemd.containerId, s"""
      apt-get update
      echo
      echo "We expect this to fail, due to dependencies missing:"
      echo
      dpkg -i $marathonDebPackage
      apt-get install -f -y
    """)
    execBash(systemd.containerId, "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed").trim shouldBe("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(systemd.containerId, s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """)
  }

  "the package causes Marathon to be started on boot" in {
    execBash(systemd.containerId, """
      if [ -f /etc/systemd/system/multi-user.target.wants/marathon.service ]; then
        echo Installed
      else
        echo Not installed
      fi
    """).trim.shouldBe("Installed")
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }
}

class Ubuntu1604Test(marathonDebPackage: String) extends MesosTest {

  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()
    systemd = runContainer("--name", "ubuntu1604", "-v", s"${packagePath}:/var/packages", "marathon-package-test:ubuntu1604")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(systemd.containerId, s"""
      apt-get update
      echo
      echo "We expect this to fail, due to dependencies missing:"
      echo
      dpkg -i $marathonDebPackage
      apt-get install -f -y
    """)
    execBash(systemd.containerId, "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed").trim shouldBe("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(systemd.containerId, s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """)
  }

  "the package causes Marathon to be started on boot" in {
    execBash(systemd.containerId, """
      if [ -f /etc/systemd/system/multi-user.target.wants/marathon.service ]; then
        echo Installed
      else
        echo Not installed
      fi
    """).trim.shouldBe("Installed")
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }
}

class Ubuntu1404Test extends MesosTest {

  var mesos: Container = _
  var ubuntu: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()
    ubuntu = runContainer("--name", "ubuntu1404", "-v", s"${packagePath}:/var/packages", "marathon-package-test:ubuntu1404")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(ubuntu.containerId, """
      apt-get update
      echo
      echo "We expect this to fail, due to dependencies missing:"
      echo
      dpkg -i /var/packages/marathon_*.ubuntu1404_all.deb
      apt-get install -f -y
    """)
    execBash(ubuntu.containerId, "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed").trim shouldBe("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(ubuntu.containerId, s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      service marathon restart
    """)
  }

  "the package causes Marathon to be started on boot" in {
    execBash(ubuntu.containerId, """
      if [ -f /etc/rc1.d/K*marathon ]; then
        echo Installed
      else
        echo Not installed
      fi
    """).trim.shouldBe("Installed")
  }

  "A log file is created in /var/log/marathon/marathon.log, and is not empty" in {
    implicit val patienceConfig = somewhatPatient
    eventually {
      execBash(ubuntu.containerId,
        s"""wc -l /var/log/marathon/marathon.log""").trim.split(" ").head.toInt should be >= 0
    }
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }
}

class Centos7Test extends MesosTest {

  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()
    systemd = runContainer("--name", "centos7", "-v", s"${packagePath}:/var/packages", "marathon-package-test:centos7")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(systemd.containerId, """
      yum install -y /var/packages/marathon-*el7.noarch.rpm
    """)
    execBash(systemd.containerId, "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed").trim shouldBe("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(systemd.containerId, s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """)
  }

  "the package causes Marathon to be started on boot" in {
    execBash(systemd.containerId, """
      if [ -f /etc/systemd/system/multi-user.target.wants/marathon.service ]; then
        echo Installed
      else
        echo Not installed
      fi
    """).trim.shouldBe("Installed")
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }
}

class Centos6Test extends MesosTest {
  var mesos: Container = _
  var systemv: Container = _
  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()
    systemv = runContainer(
      "--name", "centos6", "-v", s"${packagePath}:/var/packages", "marathon-package-test:centos6")

    val hostname = execBash(systemv.containerId, "cat /etc/hostname").trim

    // We do this so that Marathon can properly detect the container's IP address
    System.err.println(s"Set the hostname")
    execBashWithoutCapture(systemv.containerId, s"""
      hostname ${hostname}
      cat <<-EOF > /etc/sysconfig/network
NETWORKING=yes
HOSTNAME=${hostname}
EOF
    """)
    execBash(systemv.containerId, """hostname""").trim shouldBe hostname

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(systemv.containerId, """
      yum install -y /var/packages/marathon-*el6.noarch.rpm
    """)
    execBash(systemv.containerId, "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed").trim shouldBe("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(systemv.containerId, s"""
      echo "export MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "export MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      service marathon restart
    """)
  }

  "the package causes Marathon to be started on boot" in {
    execBash(systemv.containerId, """
      if [ -f /etc/rc1.d/K*marathon ]; then
        echo Installed
      else
        echo Not installed
      fi
    """).trim.shouldBe("Installed")
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }

  "A log file is created in /var/log/marathon/marathon, and is not empty" in {
    implicit val patienceConfig = somewhatPatient
    eventually {
      execBash(systemv.containerId,
        s"""wc -l /var/log/marathon/marathon.log""").trim.split(" ").head.toInt should be >= 0
    }
  }
}

// Test the sbt-native-packager docker produced image
class DockerImageTest extends MesosTest {
  val tag = sys.env.getOrElse("DOCKER_TAG", %%(pwd/up/up/'version, "docker").out.string.trim)
  val image = s"mesosphere/marathon:${tag}"

  var mesos: Container = _
  var dockerMarathon: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()

    System.err.println(s"Using docker image ${image}")
    val startHookFile = getTmpFile()
    write.over(startHookFile, s"""
      |#!/bin/bash
      |touch /tmp/hello-world
      |
      |cat <<-EOF > /marathon/start-hook.env
      |export MARATHON_WEBUI_URL=http://test-host:port
      |EOF
      |""".stripMargin)
    %("chmod", "+x", startHookFile)

    dockerMarathon = runContainer(
      "--name", "docker-marathon",
      "-v", s"${startHookFile}:/marathon/start-hook.sh",
      "-e", "HOOK_MARATHON_START=/marathon/start-hook.sh",
      "-e", s"MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos",
      "-e", s"MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon",
      image)
  }

  "The specified HOOK_MARATHON_START file is run" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(dockerMarathon.containerId, "find /tmp/hello-world").trim.shouldBe("/tmp/hello-world")
    }
  }

  "The resulting start-hook.env file is sourced" in {
    // Round about way of testing this; the HOOK_MARATHON_START file creates an env file which sets this parameter
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId, s"curl ${dockerMarathon.ipAddress}:8080/v2/info") should include("http://test-host:port")
    }
  }

  "The installed Marathon registers and connects to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId,
        s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
    }
  }
}

def help(testNames: Seq[String]): Unit = {
  println(s"""
Usage: ./test.sc [tests to run]

Description:

    Runs the packaging tests. Please review README.md for more details.

Environment variables:

    SKIP_CLEANUP: Define (with any value) if you'd like the tests to leave the docker containers running for manual
                  inspection.

Parameters:

    [tests to run]:

      - "all": Causes all tests to be run
      _ "{some-substring}": Run the suites with the matching substring. Case insensitive.

      Tests: ${testNames.sorted.mkString(", ")}
""")
}

@main
def main(args: String*): Unit = {
  def name(t: MesosTest): String =
    t.getClass.getSimpleName.split("$").last

  val tests = Seq(
    new Debian8Test("/var/packages/marathon_*.debian8_all.deb"),
    new Debian8Test("/var/packages/marathon_*.debian9_all.deb"),
    new Centos7Test,
    new Centos6Test,
    new Ubuntu1404Test,
    new Ubuntu1604Test("/var/packages/marathon_*.ubuntu1604_all.deb"),
    new Ubuntu1604Test("/var/packages/marathon_*.ubuntu1804_all.deb"),
    //new DockerImageTest
  )
  val predicate: (String => Boolean) = args match {
    case Seq("all") =>
      { _: String => true }
    case Seq(substring) =>
      { s: String => s.toLowerCase contains substring.toLowerCase }
    case _ =>
      help(tests.map(name))
      sys.exit(1)
      ???
  }

  val testsToRun = tests.filter { t => predicate(name(t))}
  testsToRun.foreach(run(_))
  val results = testsToRun.map { t => Await.result(t.result, 1.hour) }
  if (!results.forall(_ == true)) {
    System.err.println("There were errors")
    sys.exit(1)
  }
}
