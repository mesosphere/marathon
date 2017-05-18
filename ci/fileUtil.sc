#!/usr/bin/env amm

import ammonite.ops._
import ammonite.ops.ImplicitWd._

import java.security.MessageDigest
import javax.xml.bind.annotation.adapters.HexBinaryAdapter

val SHA_EXTENSION = "sha1"

/**
 * Creates the SHA-1 for a given file
 */
def createSha1OfFile(file: Path): String = {
  val sha1 = MessageDigest.getInstance("SHA-1");
  sha1.update(read.bytes! file)
  (new HexBinaryAdapter().marshal(sha1.digest())).toLowerCase
}

/**
 * Creates a SHA-1 file for a give file.
 * Given a file "foo.txt", a "foo.txt.sha1" will be created with the sha.
 */
def writeSha1ForFile(file: Path): Unit = {
  val sha1File = (file / up) / (s"${file.last}.$SHA_EXTENSION")
  rm! sha1File
  write(sha1File, createSha1OfFile(file))
}
