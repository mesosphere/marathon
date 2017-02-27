package mesosphere.marathon
package stream

import akka.NotUsed
import akka.stream.scaladsl._
import java.nio.charset.StandardCharsets.UTF_8
import akka.util.ByteString
import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarConstants }
import scala.annotation.tailrec

/**
  * Akka stream flows for reading and writing tar balls. Combine with Akka streams Compression flow for simple
  * compressed artifact.
  */
object TarFlow {
  private val recordSize = 512
  private val eofBlockSize = recordSize * 2

  case class TarEntry(header: TarArchiveEntry, data: ByteString)
  object TarEntry {
    def apply(
      name: String,
      data: ByteString,
      mode: Int = TarArchiveEntry.DEFAULT_FILE_MODE,
      user: String = System.getProperty("user.name"),
      modTime: Long = System.currentTimeMillis
    ): TarEntry = {
      val header = new TarArchiveEntry(name)
      header.setSize(data.size.toLong)
      header.setMode(mode)
      header.setUserName(user)
      header.setModTime(modTime)
      TarEntry(header, data)
    }
  }

  /**
    * Tar reader stages which keep track of what we're currently reading in a stream, and yields items as they are
    * available
    */
  private trait ReaderStage {
    def apply(data: ByteString): (ReaderStage, List[TarEntry], ByteString)
  }

  /**
    * Compute the amount of padding needed to make it fit a chunkSize boundary
    */
  private def calcPaddedSize(size: Int, chunkSize: Int) = {
    val leftover = size % chunkSize
    if (leftover == 0)
      size
    else
      size + (chunkSize - leftover)
  }

  /**
    * Stage used to read the contents of a tar entry. We read size + some padding, drop the padding and give it to the
    * andThen function.
    *
    * @param size The size of the contents we expect to read.
    * @param andThen function which describes the next staged
    * @param readSoFar previous offered data which did not satisfy the amount we wanted
    */
  private case class ReadingPaddedChunk(
      size: Int, andThen: ByteString => (ReaderStage, List[TarEntry]), readSoFar: ByteString = ByteString.empty
  ) extends ReaderStage {
    val paddedSize = calcPaddedSize(size, recordSize)
    def apply(data: ByteString): (ReaderStage, List[TarEntry], ByteString) = {
      val accum = readSoFar ++ data
      if (accum.length > paddedSize) {
        val (entryData, rest) = accum.splitAt(paddedSize)
        val (nextStage, tarEntries) = andThen(entryData.take(size))
        (nextStage, tarEntries, rest)
      } else {
        (copy(readSoFar = accum), Nil, ByteString.empty)
      }
    }
  }

  /**
    * When we hit an EOF record (all 0's), we consume and drop the rest of the stream
    */
  private case object Terminal extends ReaderStage {
    def apply(data: ByteString): (ReaderStage, List[TarEntry], ByteString) =
      (Terminal, Nil, ByteString.empty)
  }

  /**
    * Object containing logic for reading a tarball
    *
    * Chunking and padding is handled by ReadingPaddedChunk
    */
  private object Readers {
    def reading(nameOverride: Option[String] = None) =
      ReadingPaddedChunk(recordSize, andThen = { headerBytes =>
        lazy val entry = new TarArchiveEntry(headerBytes.toArray)
        if (headerBytes.forall(_ == 0)) {
          // that's all folks!
          (Terminal, Nil)
        } else if (entry.isGNULongNameEntry) {
          (ReadingPaddedChunk(entry.getSize.toInt, andThen = resumeWithLongName), Nil)
        } else if (entry.isPaxHeader) {
          throw new RuntimeException("Pax headers not supported")
        } else if (entry.isGNUSparse) {
          throw new RuntimeException("GNU Sparse headers not supported")
        } else if (entry.getSize > Int.MaxValue) {
          throw new RuntimeException(s"Entries larger than ${Int.MaxValue} not supported")
        } else {
          nameOverride.foreach(entry.setName)
          (ReadingPaddedChunk(entry.getSize.toInt, andThen = yieldEntry(entry)), Nil)
        }
      })

    /**
      * If a name is > 100 chars, then gnu tar will output something like this:
      *
      * - TarAchiveEntry - LONG NAME COMING of size X
      * - PaddedData containing the long name
      * - TarArchiveEntry - Actual file entry for previously specified long name
      * - PaddedData - Entry's data
      *
      * This function is called with the long name bytes. We resume reading the next TarArchiveEntry and specify that it
      * should use the name read here.
      */
    private def resumeWithLongName(nameData: ByteString): (ReaderStage, List[TarEntry]) = {
      val withoutNullTerm = if (nameData.last == 0)
        nameData.dropRight(1)
      else
        nameData

      (reading(nameOverride = Some(withoutNullTerm.utf8String)), Nil)
    }

    /**
      * The andThen behavior we follow when a TarArchiveEntry's padded data is consumed
      */
    private def yieldEntry(header: TarArchiveEntry)(data: ByteString): (ReaderStage, List[TarEntry]) = {
      (reading(), List(TarEntry(header, data)))
    }
  }

  /**
    * We recursively apply the stages until all of the current available data is consumed.
    */
  @tailrec private def process(stage: ReaderStage, data: ByteString, entries: List[TarEntry] = Nil): (ReaderStage, List[TarEntry]) =
    if (data.isEmpty) {
      (stage, entries)
    } else {
      val (nextStage, newEntries, remaining) = stage(data)
      process(nextStage, remaining, entries ++ newEntries)
    }

  val terminalChunk =
    ByteString.newBuilder.putBytes(Array.ofDim[Byte](eofBlockSize)).result

  /**
    * Flow which yields complete tar entry records (with corresponding data) as they are available.
    *
    * Entries are streamed but their contents are not.
    *
    * Does not throw an error or indicate if a partial tarball is provided. Combine with gzip compression to have the
    * http://doc.akka.io/api/akka/2.4/akka/stream/scaladsl/Compression$.html stream fail on error / early termination.
    *
    * Basic GNU tar features supported only (no PAX extensions, or sparse tarballs)
    */
  val reader: Flow[ByteString, TarEntry, NotUsed] = {
    Flow[ByteString].statefulMapConcat { () =>
      var stage: ReaderStage = Readers.reading()

      { data: ByteString =>
        val (nextStage, entries) = process(stage, data)
        stage = nextStage
        entries
      }
    }
  }

  /**
    * Flow which converts tar entries into a series of padded ByteStrings, (one ByteString for each Tar
    * entry). ByteStrings are concatenated together to make a tarball.
    */
  val writer: Flow[TarEntry, ByteString, NotUsed] = {
    Flow[TarEntry].map {
      case TarEntry(header, data) =>
        val buffer = Array.ofDim[Byte](recordSize)
        val builder = ByteString.newBuilder
        def appendHeader(header: TarArchiveEntry): Unit = {
          header.writeEntryHeader(buffer)
          builder ++= buffer
        }

        def padToBoundary(): Unit =
          while (builder.length % recordSize != 0)
            builder += 0

        val nameAsBytes = header.getName.getBytes(UTF_8)
        if (nameAsBytes.length > TarConstants.NAMELEN) {
          val longNameHeader = new TarArchiveEntry(TarConstants.GNU_LONGLINK, TarConstants.LF_GNUTYPE_LONGNAME)
          longNameHeader.setSize(nameAsBytes.length.toLong + 1L) // +1 for null
          appendHeader(longNameHeader)
          builder ++= nameAsBytes
          builder += 0
          padToBoundary()
        }

        header.setSize(data.length.toLong) // just in case
        appendHeader(header)
        builder ++= data
        padToBoundary()

        builder.result()
    }.concat(Source.single(terminalChunk))
  }
}
