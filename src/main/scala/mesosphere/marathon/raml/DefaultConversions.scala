package mesosphere.marathon
package raml

import mesosphere.marathon.stream.Implicits._

/**
  * All conversions for standard scala types.
  */
trait DefaultConversions {

  def identityConversion[A]: Writes[A, A] = Writes{ a => a }

  implicit val intIdentityWrites: Writes[Int, Int] = identityConversion[Int]
  implicit val longIdentityWrites: Writes[Long, Long] = identityConversion[Long]
  implicit val doubleIdentityWrites: Writes[Double, Double] = identityConversion[Double]
  implicit val stringIdentityWrites: Writes[String, String] = identityConversion[String]
  implicit val booleanIdentityWrites: Writes[Boolean, Boolean] = identityConversion[Boolean]

  implicit def optionConversion[A, B](implicit writer: Writes[A, B]): Writes[Option[A], Option[B]] = Writes { option =>
    option.map(writer.write)
  }

  implicit def seqConversion[A, B](implicit writer: Writes[A, B]): Writes[Seq[A], Seq[B]] = Writes { seq =>
    seq.map(writer.write)
  }

  implicit def javaListToSeqConversion[A, B](implicit writer: Writes[A, B]): Writes[java.util.List[A], Seq[B]] = Writes { list =>
    list.toSeq.map(writer.write)
  }

  implicit def setConversion[A, B](implicit writer: Writes[A, B]): Writes[Set[A], Set[B]] = Writes { set =>
    set.map(writer.write)
  }

  implicit def mapConversion[K1, V1, K2, V2](implicit key: Writes[K1, K2], value: Writes[V1, V2]): Writes[Map[K1, V1], Map[K2, V2]] = Writes { map =>
    map.map {
      case (k, v) => key.write(k) -> value.write(v)
    }
  }
}
