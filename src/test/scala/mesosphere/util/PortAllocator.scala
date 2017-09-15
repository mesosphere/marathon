package mesosphere.util

import java.net.{ InetSocketAddress, ServerSocket }
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

object PortAllocator extends StrictLogging {

  // https://en.wikipedia.org/wiki/Ephemeral_port
  // The Internet Assigned Numbers Authority (IANA) suggests the range 49152 to 65535  for dynamic or private ports.
  // Many Linux kernels use the port range 32768 to 61000.

  // From http://mesos.apache.org/documentation/latest/port-mapping-isolator/
  // Mesos provides two ranges of ports to containers:
  //
  // • OS allocated “ephemeral” ports are assigned by the OS in a range specified for each container by Mesos.
  // • Mesos allocated “non-ephemeral” ports are acquired by a framework using the same Mesos resource offer mechanism
  //   used for cpu, memory etc. for allocation to executors/tasks as required.
  //
  // Additionally, the host itself will require ephemeral ports for network communication. You need to configure these
  // three non-overlapping port ranges on the host.

  // We reserve 1000 ephemeral ports for Marathon/Zk/Mesos Master/Mesos Agent processes to listen on:
  val EPHEMERAL_PORT_START = 32768
  val EPHEMERAL_PORT_MAX = EPHEMERAL_PORT_START + 1000
  // and use the rest of the range as ports resources for mesos agents to offer e.g. --resources="ports:[10000-110000]"
  // IMPORTANT: These two ranges should NOT overlap with each other and with the operating system's ephemeral port range
  // define in ci/set_port_range.sh!
  val PORT_RANGE_START = EPHEMERAL_PORT_MAX + 1
  val PORT_RANGE_MAX = 60000 // 60001 - 61000 is reserved for port 0 random allocation. See ci/set_port_range.sh.

  // We use 2 different atomic counters: one for ephemeral ports and one for port ranges.
  private val ephemeralPorts: AtomicInteger = new AtomicInteger(EPHEMERAL_PORT_START)
  private val rangePorts: AtomicInteger = new AtomicInteger(PORT_RANGE_START)

  // Make sure the same ephemeral port is not given out twice making port collisions less likely. We're iterating
  // over ephemeral port range trying to open a socket and if successful return the port number. Should we ever run
  // out of free ephemeral ports a RuntimeException is thrown.
  @tailrec
  private def freeSocket(): ServerSocket = {
    def tryOpenSocket(port: Int): ServerSocket = {
      val socket = new ServerSocket()
      socket.setReuseAddress(false)
      socket.bind(new InetSocketAddress("localhost", port))
      socket
    }

    val port = ephemeralPorts.incrementAndGet()
    if (port > EPHEMERAL_PORT_MAX) throw new RuntimeException("Out of ephemeral ports.")

    Try(tryOpenSocket(port)) match {
      case Success(socket) =>
        // We can't return a port if we couldn't close it successfully: theoretically it would then
        // stay bound until (after a timeout) the underlying OS decides that it's free. Hence we should
        // return only successfully closed ports and retry if closing fails.
        Try(socket.close()) match {
          case Success(_) => socket
          case Failure(ex) =>
            logger.warn(s"Failed to close allocator's socket on port $port because: ${ex.getMessage}")
            freeSocket()
        }
      case Failure(ex) =>
        logger.warn(s"Failed to provide an ephemeral port $port because: ${ex.getMessage}. Will retry again...")
        freeSocket()
    }
  }

  def ephemeralPort(): Int = {
    val socket = freeSocket()
    socket.getLocalPort
  }

  def portsRange(step: Int = 100): (Int, Int) = {
    val from = rangePorts.getAndAdd(step + 1) // port resources in mesos are inclusive
    val to = from + step
    if (to > PORT_RANGE_MAX) throw new RuntimeException("Port range is depleted.")
    (from, to)
  }
}
