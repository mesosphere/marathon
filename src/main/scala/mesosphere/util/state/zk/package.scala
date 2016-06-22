package mesosphere.util.state

import scala.language.implicitConversions
import org.apache.curator.framework.CuratorFramework

package object zk {
  implicit def toRichCurator(client: CuratorFramework): RichCuratorFramework = new RichCuratorFramework(client)
}
