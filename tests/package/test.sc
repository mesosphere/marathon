#!/ usr / bin / env amm

import $ivy.`org.scalatest::scalatest:3.0.2`
import org.scalatest._
import org.scalatest.concurrent.Eventually
import scala.concurrent.{Promise, Await}
import scala.concurrent.duration._
import ammonite.ops._
import ammonite.ops.ImplicitWd._
import scala.util.Try

val REF = sys.env.getOrElse("REF", "HEAD")
val SKIP_CLEANUP = sys.env.contains("SKIP_CLEANUP")
val MarathonVersion = %%("./version", "--ref", REF)(pwd / up / up).out.string.trim
val DOCKER_TAG = sys.env.getOrElse("DOCKER_TAG", %%(pwd / up / up / 'version, "docker", "--ref", REF).out.string.trim)

abstract class UnitTest extends FlatSpec with GivenWhenThen with Matchers with Eventually {
  val veryPatient = PatienceConfig(timeout = scaled(60.seconds), interval = scaled(1.second))
  val somewhatPatient = PatienceConfig(timeout = scaled(15.seconds), interval = scaled(250.millis))
}

case class Container(containerId: String, ipAddress: String)

trait FailureWatcher extends Suite {
  private var _resultPromise = Promise[Boolean]
  def result = _resultPromise.future
  override def run(testName: Option[String], args: Args): Status =
    try {
      val status = super.run(testName, args)
      status.whenCompleted { r => _resultPromise.tryComplete(r) }
      status
    } catch {
      case ex: Throwable =>
        _resultPromise.tryFailure(ex)
        throw ex
    }
}

trait MesosTest extends UnitTest with BeforeAndAfterAll with FailureWatcher {
  val packagePath = pwd / up / up / 'tools / 'packager / 'target / MarathonVersion
  val PackageFile = s"^marathon[_-]${MarathonVersion}-.+\\.([a-z0-9]+)(?:.noarch|_all).(rpm|deb)$$".r

  def assertPackagesCleanlyBuilt(): Unit = {
    assert(packagePath.toIO.exists, "package path ${packagePath} does not exist! Did you build packages?")
    val counts = ls(packagePath).map(_.last).collect { case PackageFile(os, ext) => (os, ext) }.groupBy(identity).mapValues(_.length)

    assert(counts.size > 0, "No packages exist!")
    assert(
      counts.forall { case (k, count) => count == 1 },
      "Some packages have multiple versions; " +
        s"please assert that ${packagePath} contains one version of each package only (for each service manager and package format)"
    )
  }

  def removeStaleDockerInstances(): Unit = {
    val images =
      %%("docker", "ps", "-a", "--filter", "label=marathon-package-test", "--format", "{{.ID}}").out.string.split("\n").filter(_ != "")

    images.foreach { id =>
      System.err.println(s"Cleaning up container ${id}")
      %%("docker", "rm", "-f", id)
    }
  }

  def runContainer(args: String*): Container = {
    val containerId = %%.apply(
      Seq(
        "docker",
        "run",
        "-d",
        "--privileged",
        "--label",
        "marathon-package-test",
        "--cap-add",
        "SYS_ADMIN",
        "-v",
        "/sys/fs/cgroup:/sys/fs/cgroup:ro"
      ) ++ args
    ).out.string.trim
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
      removeStaleDockerInstances()
    }
  }

  override def afterAll(): Unit = {
    try {
      if (!SKIP_CLEANUP) {
        removeStaleDockerInstances()
      }
    } finally super.afterAll()
  }
}

trait SystemdSpec extends MesosTest {

  def systemdUnit(systemd: => Container, mesos: => Container) {
    it should "cause Marathon to start on boot" in {
      execBash(
        systemd.containerId,
        """
        if [ -f /etc/systemd/system/multi-user.target.wants/marathon.service ]; then
          echo Installed
        else
          echo Not installed
        fi
      """
      ).trim.shouldBe("Installed")
    }

    it should "cause Marathon to register and connect to the running Mesos master" in {
      implicit val patienceConfig = veryPatient
      eventually {
        execBash(
          mesos.containerId,
          s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r"""
        ).trim shouldBe ("marathon")
      }
    }
  }
}

trait SystemvSpec extends MesosTest {

  def systemvService(systemv: => Container, mesos: => Container) {
    it should "cause Marathon to start on boot" in {
      execBash(
        systemv.containerId,
        """
        if [ -f /etc/rc1.d/K*marathon ]; then
          echo Installed
        else
          echo Not installed
        fi
      """
      ).trim.shouldBe("Installed")
    }

    it should "cause Marathon to register and connect to the running Mesos master" in {
      implicit val patienceConfig = veryPatient
      eventually {
        execBash(
          mesos.containerId,
          s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r"""
        ).trim shouldBe ("marathon")
      }
    }

    it should "log to /var/log/marathon/marathon" in {
      implicit val patienceConfig = somewhatPatient
      eventually {
        execBash(systemv.containerId, s"""wc -l /var/log/marathon/marathon.log""").trim.split(" ").head.toInt should be >= 0
      }
    }
  }
}

trait Debian9Container extends MesosTest {

  val marathonDebPackage: String
  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    assertPackagesCleanlyBuilt()
    mesos = startMesos()
    systemd = runContainer("--name", "debian9", "-v", s"${packagePath}:/var/packages", "marathon-package-test:debian9")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(
      systemd.containerId,
      s"""
      apt-get update -o Acquire::Check-Valid-Until=false
      echo
      echo "We expect this to fail, due to dependencies missing:"
      echo
      dpkg -i $marathonDebPackage
      apt-get install -f -y
    """
    )
    execBash(
      systemd.containerId,
      "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed"
    ).trim shouldBe ("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(
      systemd.containerId,
      s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """
    )
  }
}

trait Ubuntu1604Container extends MesosTest {

  val marathonDebPackage: String
  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    assertPackagesCleanlyBuilt()
    mesos = startMesos()
    systemd = runContainer("--name", "ubuntu1604", "-v", s"${packagePath}:/var/packages", "marathon-package-test:ubuntu1604")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(
      systemd.containerId,
      s"""
      apt-get update
      echo
      echo "We expect this to fail, due to dependencies missing:"
      echo
      dpkg -i $marathonDebPackage
      apt-get install -f -y
    """
    )
    execBash(
      systemd.containerId,
      "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed"
    ).trim shouldBe ("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(
      systemd.containerId,
      s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """
    )
  }
}

class Debian9Test extends SystemdSpec with Debian9Container with MesosTest {
  override val marathonDebPackage = s"/var/packages/marathon_${MarathonVersion}-*.debian9_all.deb"

  "Marathon Debian 9 package" should behave like systemdUnit(systemd, mesos)
}

class Ubuntu1604Test extends SystemdSpec with Ubuntu1604Container with MesosTest {
  override val marathonDebPackage = s"/var/packages/marathon_${MarathonVersion}-*.ubuntu1604_all.deb"

  "Marathon Ubuntu 16.04 package" should behave like systemdUnit(systemd, mesos)
}

class Ubuntu1804Test extends SystemdSpec with Ubuntu1604Container with MesosTest {
  override val marathonDebPackage = s"/var/packages/marathon_${MarathonVersion}-*.ubuntu1804_all.deb"

  "Marathon Ubuntu 18.04 package" should behave like systemdUnit(systemd, mesos)
}

class Centos7Test extends SystemdSpec with MesosTest {

  var mesos: Container = _
  var systemd: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    assertPackagesCleanlyBuilt()
    mesos = startMesos()
    systemd = runContainer("--name", "centos7", "-v", s"${packagePath}:/var/packages", "marathon-package-test:centos7")

    System.err.println(s"Installing package...")
    // install the package
    execBashWithoutCapture(systemd.containerId, s"""
      yum install -y /var/packages/marathon-${MarathonVersion}-*el7.noarch.rpm
    """)
    execBash(
      systemd.containerId,
      "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed"
    ).trim shouldBe ("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(
      systemd.containerId,
      s"""
      echo "MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      systemctl restart marathon
    """
    )
  }

  "Marathon CentOS 7 package" should behave like systemdUnit(systemd, mesos)
}

class Centos6Test extends SystemvSpec with MesosTest {
  var mesos: Container = _
  var systemv: Container = _
  override def beforeAll(): Unit = {
    super.beforeAll()
    assertPackagesCleanlyBuilt()
    mesos = startMesos()
    systemv = runContainer("--name", "centos6", "-v", s"${packagePath}:/var/packages", "marathon-package-test:centos6")

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
    execBashWithoutCapture(systemv.containerId, s"""
      yum install -y /var/packages/marathon-${MarathonVersion}-*el6.noarch.rpm
    """)
    execBash(
      systemv.containerId,
      "[ -f /usr/share/marathon/bin/marathon ] && echo Installed || echo Not installed"
    ).trim shouldBe ("Installed")

    System.err.println(s"Configuring")
    execBashWithoutCapture(
      systemv.containerId,
      s"""
      echo "export MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos" >> /etc/default/marathon
      echo "export MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon" >> /etc/default/marathon
      service marathon restart
    """
    )
  }

  "Marathon CentOS 6 package" should behave like systemvService(systemv, mesos)
}

// Test the sbt-native-packager docker produced image
class DockerImageTest extends MesosTest {
  val image = s"mesosphere/marathon:${DOCKER_TAG}"

  var mesos: Container = _
  var dockerMarathon: Container = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    mesos = startMesos()

    System.err.println(s"Using docker image ${image}")
    val startHookFile = getTmpFile()
    write.over(
      startHookFile,
      s"""
      |#!/bin/bash
      |touch /tmp/hello-world
      |
      |cat <<-EOF > /marathon/start-hook.env
      |export MARATHON_WEBUI_URL=http://test-host:port
      |EOF
      |""".stripMargin
    )
    %("chmod", "755", startHookFile)

    dockerMarathon = runContainer(
      "--name",
      "docker-marathon",
      "-v",
      s"${startHookFile}:/marathon/start-hook.sh",
      "-e",
      "HOOK_MARATHON_START=/marathon/start-hook.sh",
      "-e",
      s"MARATHON_MASTER=zk://${mesos.ipAddress}:2181/mesos",
      "-e",
      s"MARATHON_ZK=zk://${mesos.ipAddress}:2181/marathon",
      image
    )
  }

  "The specified HOOK_MARATHON_START file" should "run" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(dockerMarathon.containerId, "find /tmp/hello-world").trim.shouldBe("/tmp/hello-world")
    }
  }

  "The resulting start-hook.env file" should "be sourced" in {
    // Round about way of testing this; the HOOK_MARATHON_START file creates an env file which sets this parameter
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId, s"curl ${dockerMarathon.ipAddress}:8080/v2/info") should include("http://test-host:port")
    }
  }

  "The installed Marathon" should "register and connect to the running Mesos master" in {
    implicit val patienceConfig = veryPatient
    eventually {
      execBash(mesos.containerId, s"""curl -s ${mesos.ipAddress}:5050/frameworks | jq '.frameworks[].name' -r""").trim shouldBe ("marathon")
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
    new Debian9Test,
    new Centos7Test,
    new Centos6Test,
    new Ubuntu1604Test,
    new Ubuntu1804Test,
    new DockerImageTest
  )
  val predicate: (String => Boolean) = args match {
    case Seq("all") => { _: String => true }
    case Seq(substring) => { s: String => s.toLowerCase contains substring.toLowerCase }
    case _ =>
      help(tests.map(name))
      sys.exit(1)
      ???
  }

  val testsToRun = tests.filter { t => predicate(name(t)) }
  testsToRun.foreach(run(_))
  val results = testsToRun.map { t => Await.result(t.result, 1.hour) }
  if (!results.forall(_ == true)) {
    System.err.println("There were errors")
    sys.exit(1)
  }
}
