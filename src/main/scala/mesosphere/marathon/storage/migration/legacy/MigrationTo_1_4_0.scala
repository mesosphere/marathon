package mesosphere.marathon
package storage.migration.legacy

import java.util.regex.Pattern

import akka.Done
import akka.stream.Materializer
import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.state.{ AppDefinition, Group }
import mesosphere.marathon.storage.LegacyStorageConfig
import mesosphere.marathon.storage.repository.{ AppRepository, DeploymentRepository, GroupRepository, PodRepository }
import mesosphere.marathon.upgrade.DeploymentPlan

import scala.async.Async._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

@SuppressWarnings(Array("ClassNames"))
class MigrationTo_1_4_0(config: Option[LegacyStorageConfig])(implicit
  ctx: ExecutionContext,
    metrics: Metrics,
    mat: Materializer) extends StrictLogging {

  private def isBrokenConstraint(constraint: Constraint): Boolean = {
    (constraint.getOperator == Constraint.Operator.LIKE ||
      constraint.getOperator == Constraint.Operator.UNLIKE) &&
      (constraint.hasValue && Try(Pattern.compile(constraint.getValue)).isFailure)
  }

  private def fixConstraints(app: AppDefinition): AppDefinition = {
    val newConstraints: Set[Constraint] = app.constraints.flatMap { constraint =>
      constraint.getOperator match {
        case Constraint.Operator.LIKE | Constraint.Operator.UNLIKE if isBrokenConstraint(constraint) =>
          // we know we have a value and the pattern doesn't compile already.
          if (constraint.getValue == "*") {
            Some(constraint.toBuilder.setValue(".*").build())
          } else {
            logger.warn(s"Removing invalid constraint $constraint from ${app.id}")
            None
          }
        case _ => Some(constraint)
      }
    }(collection.breakOut)
    app.copy(constraints = newConstraints)
  }

  private def migrateApps(appRepository: AppRepository): Future[Done] = {
    appRepository.all().collect {
      case app: AppDefinition if app.constraints.exists(isBrokenConstraint) => app
    }.mapAsync(Int.MaxValue) { app =>
      logger.info(s"Fixing  with invalid constraints: ${app.id}")
      appRepository.store(fixConstraints(app))
    }.runForeach(_ => Done)
  }

  private def fixRoot(group: Group): Group = {
    group.update(group.version) { g =>
      val apps = g.apps.map {
        case (appId, app) =>
          app match {
            case _ if app.constraints.exists(isBrokenConstraint) =>
              appId -> fixConstraints(app)
            case _ => appId -> app
          }
      }
      g.copy(apps = apps)
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def migrateRoot(groupRepository: GroupRepository): Future[Done] = async {
    val root = await(groupRepository.root())
    if (root.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint))) {
      logger.info("Updating apps in root group as they have invalid constraints")
      val updatedApps: Seq[AppDefinition] =
        root.transitiveApps.withFilter(app => app.constraints.exists(isBrokenConstraint))
          .map(fixConstraints)(collection.breakOut)
      await(groupRepository.storeRoot(fixRoot(root), updatedApps, Nil, Nil, Nil))
    } else {
      Done
    }
  }

  @SuppressWarnings(Array("all")) // async/await
  private def migratePlans(deploymentRepository: DeploymentRepository): Future[Done] = {
    deploymentRepository.all().collect {
      case plan: DeploymentPlan if plan.original.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint)) ||
        plan.target.transitiveApps.exists(app => app.constraints.exists(isBrokenConstraint)) => plan
    }.mapAsync(Int.MaxValue) { plan =>
      logger.info(s"Fixing plan ${plan.id} as it contains apps with invalid " +
        "constraints (even if they are not affected by the plan itself)")
      deploymentRepository.store(plan.copy(original = fixRoot(plan.original), target = fixRoot(plan.target)))
    }.runForeach(_ => Done)
  }

  @SuppressWarnings(Array("all")) // async/await
  def migrate(
    appRepository: AppRepository,
    groupRepository: GroupRepository, deploymentRepository: DeploymentRepository): Future[Done] = async {
    logger.info("Starting migration to 1.3.6")
    await(Future.sequence(Seq(migrateApps(appRepository), migrateRoot(groupRepository), migratePlans(deploymentRepository))))
    logger.info("Finished 1.3.6 migration")
    Done
  }

  def migrate(): Future[Done] = {
    config.fold[Future[Done]](Future.successful(Done)) { config =>
      val appRepository = AppRepository.legacyRepository(config.entityStore[AppDefinition], config.maxVersions)
      val podRepository = PodRepository.legacyRepository(config.entityStore[PodDefinition], config.maxVersions)
      val groupRepository = GroupRepository.legacyRepository(config.entityStore[Group], config.maxVersions, appRepository, podRepository)
      val deploymentRepository = DeploymentRepository.legacyRepository(config.entityStore[DeploymentPlan])
      migrate(appRepository, groupRepository, deploymentRepository)
    }
  }
}
