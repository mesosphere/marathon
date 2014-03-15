package mesosphere.marathon.state

import org.junit.Test
import org.junit.Assert._
import mesosphere.marathon.Protos
import mesosphere.marathon.api.v1.AppDefinition
import scala.collection.JavaConverters._

class AppRepositoryTest {

//  Proto for AppRepository has been removed.
//  @Test
//  def testToProtoEmpty() {
//    val emptyRepo = AppRepository()
//    val proto = emptyRepo.toProto
//
//    assertEquals(proto.getHistoryCount, 0)
//    assertEquals(proto.getHistoryList.size, 0)
//  }
//
//  @Test
//  def testToProtoNotEmpty() {
//    val app = AppDefinition()
//    val nonEmptyRepo = AppRepository(app)
//    val proto = nonEmptyRepo.toProto
//
//    assertEquals(proto.getHistoryCount, 1)
//    assertEquals(proto.getHistoryList.asScala.size, 1)
//    assertEquals(proto.getHistoryList.asScala.headOption, Some(app.toProto))
//  }
//
//  @Test
//  def mergeFromProtoEmpty() {
//    val proto = Protos.AppRepository.newBuilder().build()
//    val emptyRepo = AppRepository().mergeFromProto(proto)
//    assertTrue(emptyRepo.history.isEmpty)
//  }
//
//  @Test
//  def mergeFromProtoNotEmpty() {
//
//    val app = AppDefinition()
//
//    val proto = Protos.AppRepository.newBuilder()
//      .addAllHistory(Seq(app.toProto).asJava)
//      .build()
//
//    val nonEmptyRepo = AppRepository().mergeFromProto(proto)
//    assertTrue(nonEmptyRepo.history.nonEmpty)
//    assertEquals(nonEmptyRepo.history.headOption, Some(app))
//  }

}