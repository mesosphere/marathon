package mesosphere.marathon.state

import mesosphere.marathon.Protos
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConverters._


case class AppRepository(
  history: History[AppDefinition] = History[AppDefinition]()
) extends MarathonState[Protos.AppRepository, AppRepository] {

  def currentVersion(): Option[AppDefinition] = history.lastOption

  def toProto(): Protos.AppRepository =
    Protos.AppRepository.newBuilder()
      .addAllHistory(history.map(_.toProto).asJava)
      .build()

  def mergeFromProto(proto: Protos.AppRepository): AppRepository =
    AppRepository(
      History[AppDefinition](
        proto.getHistoryList.asScala.map { bytes =>
          AppDefinition().mergeFromProto(bytes)
        }: _*
      )
    )

  def mergeFromProto(bytes: Array[Byte]): AppRepository =
    mergeFromProto(Protos.AppRepository.parseFrom(bytes))

}

object AppRepository {

  def apply(): AppRepository =
    AppRepository(History[AppDefinition]())

  def apply(apps: AppDefinition*): AppRepository =
    AppRepository(History[AppDefinition](apps: _*))
}