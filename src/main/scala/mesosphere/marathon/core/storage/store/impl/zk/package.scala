package mesosphere.marathon
package core.storage.store.impl

import org.apache.curator.framework.CuratorFramework

import scala.language.implicitConversions

package object zk {
  implicit def toRichCurator(client: CuratorFramework): RichCuratorFramework = new RichCuratorFramework(client)
}
