import $file.helpers
import $file.bindings
import scala.annotation.tailrec
import $file.version

import akka.stream.Materializer
import akka.util.Timeout

import mesosphere.marathon.PrePostDriverCallback
import mesosphere.marathon.Protos.StorageVersion
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.marathon.storage.migration.Migration
import mesosphere.marathon.storage.repository._

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.io.StdIn

import bindings._
import version.StorageToolVersion

class DSL(unverifiedModule: => StorageToolModule)(implicit val mat: Materializer, timeout: Timeout) {
  import helpers.Helpers._

  case class AppId(path: AbsolutePathId) {
    override def toString(): String = s"AppId(${path})"
  }
  case class PodId(path: AbsolutePathId) {
    override def toString(): String = s"PodId(${path})"
  }
  case class DeploymentId(id: String)
  type InstanceId = Instance.Id
  def InstanceId(id: String) = Instance.Id.fromIdString(id)

  trait StringFormatter[T] extends (T => String) { def apply(v: T): String }
  object StringFormatter {
    def apply[T](fn: T => String): StringFormatter[T] = new StringFormatter[T] {
      override def apply(v: T): String = fn(v)
    }
  }
  implicit val InstanceIdFormatter = StringFormatter[InstanceId] { _.idString }
  implicit val AppIdFormatter = StringFormatter[AppId] { _.path.toString }
  implicit val PodIdFormatter = StringFormatter[PodId] { _.path.toString }
  implicit val DeploymentIdFormatter = StringFormatter[DeploymentId] { _.id }
  class QueryResult[T](val values: Seq[T])(implicit formatter: StringFormatter[T]) {
    def formattedValues: Seq[String] = values.map(formatter)
    override def toString(): String = {
      val b = new java.lang.StringBuilder
      b.append("Results:\n\n")
      formattedValues.foreach { v =>
        b.append("  ")
        b.append(v)
        b.append('\n')
      }
      b.toString
    }
  }

  object QueryResult {
    def apply[T](values: Seq[T])(implicit formatter: StringFormatter[T]) = new QueryResult[T](values)
  }

  implicit def stringToAppId(s: String): AppId = AppId(AbsolutePathId(s))
  implicit def stringToPodId(s: String): PodId = PodId(AbsolutePathId(s))
  implicit def stringToPathId(s: String): AbsolutePathId = AbsolutePathId(s)

  implicit def appIdToPath(appId: AppId): AbsolutePathId = appId.path
  implicit def podIdToPath(podId: PodId): AbsolutePathId = podId.path

  def listApps(containing: String = null, limit: Int = Int.MaxValue)(
    implicit module: StorageToolModule, timeout: Timeout): QueryResult[AppId] = {
    val predicates = List(
      Option(containing).map { c => { pathId: AbsolutePathId => pathId.toString.contains(c) } }
    ).flatten
    // TODO - purge related deployments?

    QueryResult {
      await(module.appRepository.ids)
        .filter { app =>
          predicates.forall { p => p(app) }
        }
        .sortBy(_.toString)
        .take(limit)
        .map(AppId(_))
    }
  }

  def listPods(containing: String = null, limit: Int = Int.MaxValue)(
    implicit module: StorageToolModule, timeout: Timeout): QueryResult[PodId] = {
    val predicates = List(
      Option(containing).map { c => { pathId: AbsolutePathId => pathId.toString.contains(c) } }
    ).flatten
    // TODO - purge related deployments?

    QueryResult {
      await(module.podRepository.ids)
        .filter { pod =>
          predicates.forall { p => p(pod) }
        }
        .sortBy(_.toString)
        .take(limit)
        .map(PodId(_))
    }
  }

  def listInstances(
    forApp: AppId = null,
    forPod: PodId = null,
    containing: String = null,
    limit: Int = Int.MaxValue)(
    implicit module: StorageToolModule, timeout: Timeout): QueryResult[InstanceId] = {
    val predicates: List[(InstanceId => Boolean)] = List(
      Option(containing).map { c =>
        { instanceId: InstanceId => instanceId.toString.contains(c) }
      }
    ).flatten

    val input = (Option(forApp), Option(forPod)) match {
      case (None, Some(podId)) =>
        module.instanceRepository.instances(podId.path)
      case (Some(appId), None) =>
        module.instanceRepository.instances(appId.path)
      case (None, None) =>
        module.instanceRepository.ids
      case (Some(_), Some(_)) =>
        throw new RuntimeException("cannot specify both podId and appId")
    }
    QueryResult {
      await(input)
        .filter { instanceId =>
          predicates.forall { p => p(instanceId) }
        }
        .sorted
        .take(limit)
    }
  }

  def listDeployments(
    limit: Int = Int.MaxValue)(
    implicit module: StorageToolModule,
      timeout: Timeout): QueryResult[DeploymentId] = {

    QueryResult {
      await(module.deploymentRepository.ids)
        .sorted
        .take(limit)
        .map(DeploymentId(_))
    }
  }

  trait PurgeStrategy[T] {
    val purgeDescription: String
    def `purge!`(values: Seq[T]): Unit
  }

  implicit def UnwrapQueryResult[T](qr: QueryResult[T]): Seq[T] = qr.values
  implicit def DeploymentPurgeStrategy(implicit module: StorageToolModule): PurgeStrategy[DeploymentId] = new PurgeStrategy[DeploymentId] {
    val purgeDescription = "deployments"
    override def `purge!`(values: Seq[DeploymentId]): Unit = {
      values.foreach { v =>
        module.deploymentRepository.delete(v)
        println(s"Purged deployment: ${DeploymentIdFormatter(v)}")
      }
    }
  }
  implicit def InstancePurgeStrategy(implicit module: StorageToolModule): PurgeStrategy[InstanceId] = new PurgeStrategy[InstanceId] {
    val purgeDescription = "instances"
    override def `purge!`(values: Seq[InstanceId]): Unit = {
      values.foreach { v =>
        module.instanceRepository.delete(v)
        println(s"Purged instance: ${InstanceIdFormatter(v)}")
      }
    }
  }

  implicit def PodPurgeStrategy(implicit module: StorageToolModule): PurgeStrategy[PodId] = new PurgeStrategy[PodId] {
    val purgeDescription = "pods and associated instances"
    override def `purge!`(podIds: Seq[PodId]): Unit = {
      // Remove from rootGroup
      val rootGroup = await(module.groupRepository.root)
      val newGroup = podIds.foldLeft(rootGroup) { (r, podId) => r.removePod(podId.path) }
      module.groupRepository.storeRoot(newGroup, Nil, deletedPods = podIds.map(_.path), updatedPods = Nil, deletedApps = Nil)
      println(s"Removed ${podIds.map(PodIdFormatter).toList} from root group")

      podIds.foreach { podId =>
        val instances = await(module.instanceRepository.instances(podId.path))
        InstancePurgeStrategy.`purge!`(instances)
        module.podRepository.delete(podId.path)
        println(s"Purged pod ${podId}")
      }
    }
  }

  implicit def AppPurgeStrategy(implicit module: StorageToolModule): PurgeStrategy[AppId] = new PurgeStrategy[AppId] {
    val purgeDescription = "apps and associated instances"
    override def `purge!`(appIds: Seq[AppId]): Unit = {
      // Remove from rootGroup
      val rootGroup = await(module.groupRepository.root)
      val newGroup = appIds.foldLeft(rootGroup) { (r, appId) => r.removeApp(appId.path) }
      module.groupRepository.storeRoot(newGroup, Nil, deletedApps = appIds.map(_.path), updatedPods = Nil, deletedPods = Nil)
      println(s"Removed ${appIds.map(AppIdFormatter).toList} from root group")

      appIds.foreach { appId =>
        val instances = await(module.instanceRepository.instances(appId.path))
        InstancePurgeStrategy.`purge!`(instances)
        module.appRepository.delete(appId.path)
        println(s"Purged app ${appId}")
      }
    }
  }

  def confirm[T](id: Int)(default: T)(fn: => T): T = {
    print(s"To confirm the operation, please type $id: ")
    val confirmedId = StdIn.readInt()

    if (id != confirmedId) {
      println(s"The operation has not been confirmed!")
      default
    }
    else {
      fn
    }
  }

  def purge[T](values: Seq[T])(implicit purgeStrategy: PurgeStrategy[T], formatter: StringFormatter[T]): Unit = {
    println()
    println(s"Are you sure you wish to purge the following ${purgeStrategy.purgeDescription}?")
    println()
    val formattedValues = values.map(formatter)
    formattedValues.foreach { v => println(s"  ${v}") }
    println()
    confirm(formattedValues.hashCode)(()) {
      purgeStrategy.`purge!`(values)
      println()
      println("Done")
      println()
      println("Note: The leading Marathon will need to be restarted to see changes")
    }
  }

  def purge[T](queryResult: QueryResult[T])(implicit purgeStrategy: PurgeStrategy[T], formatter: StringFormatter[T]): Unit = {
    purge(queryResult.values)
  }

  def purge[T](value: T)(implicit purgeStrategy: PurgeStrategy[T], formatter: StringFormatter[T]): Unit = {
    purge(Seq(value))
  }

  def migrate(): Seq[String] = {
    def versionToString(version: StorageVersion): String = {
      s"${version.getMajor}.${version.getMinor}.${version.getPatch}-${version.getFormat}"
    }

    println()
    println("Are you sure you wish to perform the data migration? Before proceeding please make sure there are no running Marathon instances")
    println()

    confirm(System.currentTimeMillis.hashCode)(Seq.empty[String]) {
      val result = unverifiedModule.migration.migrate().map(versionToString(_))
      println()
      println("Done")
      println()
      println("Note: Now you may start your Marathon instances back")
      result
    }
  }

  def help: Unit = {
    println(s"""
Marathon Storage Tool (${StorageToolVersion})
==========================

Commands:

  listApps(containing: String, limit: Int)

    description: Return (sorted) list apps in repository

    params:
      containing : List all apps with the specified string in the appId
      limit      : Limit number of apps returned

    example:

      listApps(containing = "store", limit = 5)

  listInstances(forApp: AbsolutePathId, containing: String, limit: Int)

    description: List instances
    params:
      containing : List all instances containing the specified string in their id
      limit      : Limit number of instances returned
      forApp     : List instances pertaining to the specified appId
      forPod     : List instances pertaining to the specified podId (you cannot
                   specify both forApp and forPod)

    example:

      listInstances(forApp = "/example", limit = 5)

  listDeployments(limit: Int)

    description: List deployments
    params:
      limit      : Limit number of instances returned

    example:

      listDeployments(forApp = "/example", limit = 5)

  listPods(containing: String, limit: Int)

    description: Return (sorted) list pods in repository

    params:
      containing : List all pods with the specified string in the podId
      limit      : Limit number of pods returned

    example:

      listPods(containing = "store", limit = 5)

  purge(items: T)

    description: Purge the specified items

    example:

      purge(listInstances(forApp = "/example"))
      purge(AppId("/example"))
      purge(PodId("/example"))
      purge(InstanceId("example.marathon-6f4c3d08-32e8-11ea-bd6b-b64cddbca657"))
      purge(List(PodId("/example"), PodId("/example2")))

  migrate()

    description: Perform ZK data migration to the current version, if necessary

    example:

      listApps()

  help

    description: Show this help
""")
  }

  def error(str: String): Nothing = {
    println(s"Error! ${str}")
    sys.exit(1)
    ???
  }
}
