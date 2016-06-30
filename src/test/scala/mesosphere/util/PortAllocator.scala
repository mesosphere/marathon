package mesosphere.util

import java.net.ServerSocket

object PortAllocator {
  def ephemeralPort(): Int = {
    val socket = new ServerSocket(0)
    val port = socket.getLocalPort
    socket.close()
    port
  }
}
