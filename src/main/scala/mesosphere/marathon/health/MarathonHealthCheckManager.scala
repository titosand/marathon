package mesosphere.marathon.health

import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.tasks.TaskTracker

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.ask
import akka.util.Timeout
import javax.inject.{ Named, Inject }
import scala.concurrent.Future
import scala.concurrent.duration._
import mesosphere.util.ThreadPoolContext.context
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{ RemoveHealthCheck, AddHealthCheck, EventModule }
import org.apache.mesos.MesosSchedulerDriver
import org.apache.mesos.Protos.TaskStatus
import mesosphere.marathon.MarathonSchedulerDriver

class MarathonHealthCheckManager @Inject() (
    system: ActorSystem,
    @Named(EventModule.busName) eventBus: Option[EventBus],
    taskTracker: TaskTracker) extends HealthCheckManager {

  protected[this] case class ActiveHealthCheck(
    healthCheck: HealthCheck,
    actor: ActorRef)

  protected[this] var appHealthChecks = Map[String, Set[ActiveHealthCheck]]()

  override def list(appId: String): Set[HealthCheck] =
    appHealthChecks.get(appId).fold(Set[HealthCheck]()) { activeHealthChecks =>
      activeHealthChecks.map(_.healthCheck)
    }

  protected[this] def find(
    appId: String,
    healthCheck: HealthCheck): Option[ActiveHealthCheck] =
    appHealthChecks.get(appId).flatMap {
      _.find { _.healthCheck == healthCheck }
    }

  override def add(appId: String, healthCheck: HealthCheck): Unit = {
    val healthChecksForApp =
      appHealthChecks.get(appId).getOrElse(Set[ActiveHealthCheck]())

    if (!healthChecksForApp.exists { _.healthCheck == healthCheck }) {
      val ref = system.actorOf(
        Props(classOf[HealthCheckActor], appId, healthCheck, taskTracker, eventBus)
      )
      val newHealthChecksForApp =
        healthChecksForApp + ActiveHealthCheck(healthCheck, ref)
      appHealthChecks += (appId -> newHealthChecksForApp)
    }

    eventBus.foreach(_.post(AddHealthCheck(healthCheck)))
  }

  override def addAllFor(app: AppDefinition): Unit =
    app.healthChecks.foreach(add(app.id, _))

  override def remove(appId: String, healthCheck: HealthCheck): Unit = {
    for (activeHealthChecks <- appHealthChecks.get(appId)) {
      activeHealthChecks.find(_.healthCheck == healthCheck) foreach deactivate

      val newHealthChecksForApp =
        activeHealthChecks.filterNot { _.healthCheck == healthCheck }

      appHealthChecks =
        if (newHealthChecksForApp.isEmpty) appHealthChecks - appId
        else appHealthChecks + (appId -> newHealthChecksForApp)
    }

    eventBus.foreach(_.post(RemoveHealthCheck(appId)))
  }

  override def removeAll(): Unit = appHealthChecks.keys foreach removeAllFor

  override def removeAllFor(appId: String): Unit =
    for (activeHealthChecks <- appHealthChecks.get(appId)) {
      activeHealthChecks foreach deactivate
      appHealthChecks = appHealthChecks - appId
    }

  override def reconcileWith(app: AppDefinition): Unit = {
    val existingHealthChecks = list(app.id)
    val toRemove = existingHealthChecks -- app.healthChecks
    val toAdd = app.healthChecks -- existingHealthChecks
    for (hc <- toRemove) remove(app.id, hc)
    for (hc <- toAdd) add(app.id, hc)
  }

  override def update(taskStatus: TaskStatus): Health =
    Health(taskStatus.getTaskId.getValue)

  override def status(
    appId: String,
    taskId: String): Future[Seq[Option[Health]]] = {
    import HealthCheckActor.GetTaskHealth
    implicit val timeout: Timeout = Timeout(2, SECONDS)
    appHealthChecks.get(appId) match {
      case Some(activeHealthCheckSet) => Future.sequence(
        activeHealthCheckSet.toSeq.map {
          case ActiveHealthCheck(_, actor) => (actor ? GetTaskHealth(taskId)).mapTo[Option[Health]]
        }
      )
      case None => Future.successful(Nil)
    }
  }

  protected[this] def deactivate(healthCheck: ActiveHealthCheck): Unit =
    system stop healthCheck.actor

}
