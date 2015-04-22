package mesosphere.mesos.scale

object DisplayHelpers {
  /** Formatting function. The first parameter is the input string and the second parameter the desired length. */
  type ColumnFormat = ((String, Int) => String)

  /** Left align field. */
  def left(str: String, length: Int) = {
    if (str.length > length)
      str.substring(length - 3) + "..."
    else
      str + (" " * (length - str.length))
  }

  def right(str: String, length: Int) = {
    if (str.length > length)
      "..." + str.substring(length - 3)
    else
      (" " * (length - str.length)) + str
  }

  def printTable(columnFormats: Seq[ColumnFormat], rowsWithAny: Seq[IndexedSeq[Any]]): Unit = {
    val rows = rowsWithAny.map(_.map(_.toString))

    val columns = columnFormats.size

    val maxColumnLengths: IndexedSeq[Int] = for {
      column <- 0 until columns
    } yield rows.iterator.map(_(column).length).max

    for (row <- rows) {
      val formatted = columnFormats.zipWithIndex.zip(row).map {
        case ((format, index), value) => format(value, maxColumnLengths(index))
      }

      println(formatted.mkString(" "))
    }
  }

  def withUnderline(header: IndexedSeq[String]): Seq[IndexedSeq[String]] = {
    IndexedSeq(
      header,
      header.map { str => "-" * str.length }
    )
  }
}
