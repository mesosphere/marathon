package mesosphere.marathon.io

import java.math.BigInteger
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.io.FilenameUtils.getName

trait PathFun {

  private[this] def md = MessageDigest.getInstance("MD5")

  def mdHex(in: String): String = {
    val ret = md
    ret.update(in.getBytes("UTF-8"), 0, in.length)
    new BigInteger(1, ret.digest()).toString(16)
  }

  def fileName(url: String): String = getName(new URL(url).getFile)

  def uniquePath(url: String): String = s"${mdHex(url)}/${fileName(url)}"
}

