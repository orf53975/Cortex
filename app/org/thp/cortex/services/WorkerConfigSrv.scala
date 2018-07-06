package org.thp.cortex.services

import scala.concurrent.{ ExecutionContext, Future }

import play.api.libs.json._

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import org.thp.cortex.models._
import org.scalactic.Accumulation._

import org.elastic4play.{ AttributeCheckingError, NotFoundError }
import org.elastic4play.controllers.Fields
import org.elastic4play.database.ModifyConfig
import org.elastic4play.services._

trait WorkerConfigSrv {
  val userSrv: UserSrv
  val createSrv: CreateSrv
  val updateSrv: UpdateSrv
  val workerConfigModel: WorkerConfigModel
  val organizationSrv: OrganizationSrv
  val findSrv: FindSrv
  implicit val ec: ExecutionContext
  implicit val mat: Materializer

  val workerType: WorkerType.Type

  def definitions: Future[Map[String, BaseConfig]]

  protected def buildDefinitionMap(definitionSource: Source[WorkerDefinition, NotUsed]): Future[Map[String, BaseConfig]] = {
    definitionSource
      .filter(_.baseConfiguration.isDefined)
      .map(d ⇒ d.copy(configurationItems = d.configurationItems.map(_.copy(required = false))))
      .groupBy(200, _.baseConfiguration.get) // TODO replace groupBy by fold to prevent "too many streams" error
      .map(d ⇒ BaseConfig(d.baseConfiguration.get, Seq(d.name), d.configurationItems, None))
      .reduce(_ + _)
      .filterNot(_.items.isEmpty)
      .mergeSubstreams
      .mapMaterializedValue(_ ⇒ NotUsed)
      .runWith(Sink.seq)
      .map { baseConfigs ⇒
        (BaseConfig.global +: baseConfigs)
          .map(c ⇒ c.name -> c)
          .toMap
      }
  }

  def getForUser(userId: String, configName: String): Future[BaseConfig] = {
    userSrv.getOrganizationId(userId)
      .flatMap(organizationId ⇒ getForOrganization(organizationId, configName))
  }

  def getForOrganization(organizationId: String, configName: String): Future[BaseConfig] = {
    import org.elastic4play.services.QueryDSL._
    for {
      analyzerConfig ← findForOrganization(organizationId, "name" ~= configName, Some("0-1"), Nil)
        ._1
        .runWith(Sink.headOption)
      d ← definitions
      baseConfig ← d.get(configName).fold[Future[BaseConfig]](Future.failed(NotFoundError(s"config $configName not found")))(Future.successful)
    } yield baseConfig.copy(config = analyzerConfig)
  }

  def create(organization: Organization, fields: Fields)(implicit authContext: AuthContext): Future[WorkerConfig] = {
    createSrv[WorkerConfigModel, WorkerConfig, Organization](workerConfigModel, organization, fields.set("type", workerType.toString))
  }

  def update(analyzerConfig: WorkerConfig, fields: Fields)(implicit authContext: AuthContext): Future[WorkerConfig] = {
    updateSrv(analyzerConfig, fields, ModifyConfig.default)
  }

  def updateOrCreate(userId: String, analyzerConfigName: String, config: JsObject)(implicit authContext: AuthContext): Future[BaseConfig] = {
    for {
      organizationId ← userSrv.getOrganizationId(userId)
      organization ← organizationSrv.get(organizationId)
      baseConfig ← getForOrganization(organizationId, analyzerConfigName)
      validatedConfig ← baseConfig.items.validatedBy(_.read(config))
        .map(_.filterNot(_._2 == JsNull))
        .fold(c ⇒ Future.successful(Fields.empty.set("config", JsObject(c).toString).set("name", analyzerConfigName)), errors ⇒ Future.failed(AttributeCheckingError("analyzerConfig", errors.toSeq)))
      newAnalyzerConfig ← baseConfig.config.fold(create(organization, validatedConfig))(analyzerConfig ⇒ update(analyzerConfig, validatedConfig))
    } yield baseConfig.copy(config = Some(newAnalyzerConfig))
  }

  private def updateDefinitionConfig(definitionConfig: Map[String, BaseConfig], analyzerConfig: WorkerConfig): Map[String, BaseConfig] = {
    definitionConfig.get(analyzerConfig.name())
      .fold(definitionConfig) { baseConfig ⇒
        definitionConfig + (analyzerConfig.name() -> baseConfig.copy(config = Some(analyzerConfig)))
      }
  }

  def listConfigForUser(userId: String): Future[Seq[BaseConfig]] = {
    import org.elastic4play.services.QueryDSL._
    for {
      configItems ← definitions
      analyzerConfigItems = configItems
      analyzerConfigs ← findForUser(userId, any, Some("all"), Nil)
        ._1
        .runFold(analyzerConfigItems) { (definitionConfig, analyzerConfig) ⇒ updateDefinitionConfig(definitionConfig, analyzerConfig) }
    } yield analyzerConfigs.values.toSeq
  }

  def findForUser(userId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[WorkerConfig, NotUsed], Future[Long]) = {
    val configs = userSrv.getOrganizationId(userId)
      .map(organizationId ⇒ findForOrganization(organizationId, queryDef, range, sortBy))
    val configSource = Source.fromFutureSource(configs.map(_._1)).mapMaterializedValue(_ ⇒ NotUsed)
    val configTotal = configs.flatMap(_._2)
    configSource -> configTotal
  }

  def findForOrganization(organizationId: String, queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[WorkerConfig, NotUsed], Future[Long]) = {
    import org.elastic4play.services.QueryDSL._
    find(and(withParent("organization", organizationId), "type" ~= workerType, queryDef), range, sortBy)
  }

  def find(queryDef: QueryDef, range: Option[String], sortBy: Seq[String]): (Source[WorkerConfig, NotUsed], Future[Long]) = {
    findSrv[WorkerConfigModel, WorkerConfig](workerConfigModel, queryDef, range, sortBy)
  }
}
