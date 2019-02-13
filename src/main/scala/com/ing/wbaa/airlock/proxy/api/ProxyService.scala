package com.ing.wbaa.airlock.proxy.api

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import com.ing.wbaa.airlock.proxy.api.directive.ProxyDirectives
import com.ing.wbaa.airlock.proxy.config.AtlasSettings
import com.ing.wbaa.airlock.proxy.data._
import com.ing.wbaa.airlock.proxy.provider.aws.AwsErrorCodes
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait ProxyService extends LazyLogging {

  // no validation of request currently
  // once we get comfortable with get/put/del we can add permCheck
  import ProxyDirectives._
  import akka.http.scaladsl.server.Directives._

  protected[this] implicit def system: ActorSystem

  protected[this] implicit def executionContext: ExecutionContext

  // Request Handler methods
  protected[this] def executeRequest(request: HttpRequest, userSTS: User): Future[HttpResponse]

  // Authentication methods
  protected[this] def areCredentialsActive(awsRequestCredential: AwsRequestCredential): Future[Option[User]]

  // AWS Signature methods
  protected[this] def isUserAuthenticated(httpRequest: HttpRequest, awsSecretKey: AwsSecretKey): Boolean

  // Authorization methods
  protected[this] def isUserAuthorizedForRequest(request: S3Request, user: User, clientIPAddress: RemoteAddress, headerIPs: HeaderIPs): Boolean

  // Atlas Lineage
  protected[this] def atlasSettings: AtlasSettings

  protected[this] def createLineageFromRequest(httpRequest: HttpRequest, userSTS: User, clientIPAddress: RemoteAddress): Future[LineageResponse]

  protected[this] def emitEvent(s3Request: S3Request, method: HttpMethod, principalId: String, clientIPAddress: RemoteAddress): Future[Done]

  val proxyServiceRoute: Route =
    withoutSizeLimit {
      (extractClientIP & extractHeaderIPs) { (clientIPAddress, headerIPs) =>
        logger.debug(s"Extracted Client IP: " +
          s"${clientIPAddress.toOption.map(_.getHostAddress).getOrElse("unknown")}")
        logger.debug(s"Extracted headers IPs: ${headerIPs}")
        extractRequest { httpRequest =>
          extracts3Request { s3Request =>
            onComplete(areCredentialsActive(s3Request.credential)) {
              case Success(Some(userSTS: User)) =>
                logger.debug(s"Credentials active for request, user retrieved: $userSTS")
                processRequestForValidUser(clientIPAddress, headerIPs, httpRequest, s3Request, userSTS)
              case Success(None) =>
                val msg = s"Request not authenticated: $s3Request"
                logger.warn(msg)
                complete(StatusCodes.Forbidden -> AwsErrorCodes.response(StatusCodes.Forbidden))
              case Failure(exception) =>
                logger.error(s"An error occurred checking authentication with STS service", exception)
                complete(StatusCodes.InternalServerError -> AwsErrorCodes.response(StatusCodes.InternalServerError))
            }
          }
        }
      }
    }

  protected[this] def processAuthorizedRequest(httpRequest: HttpRequest, s3Request: S3Request, userSTS: User, clientIPAddress: RemoteAddress): Route = {
    updateHeadersForRequest { newHttpRequest =>
      val httpResponse = executeRequest(newHttpRequest, userSTS).andThen {
        case Success(response: HttpResponse) =>
          if (atlasSettings.atlasEnabled && (response.status == StatusCodes.OK || response.status == StatusCodes.NoContent))
            // delete on AWS response 204
            createLineageFromRequest(httpRequest, userSTS, clientIPAddress)

          httpRequest.method match {
            case HttpMethods.POST | HttpMethods.PUT | HttpMethods.DELETE if response.status == StatusCodes.OK || response.status == StatusCodes.NoContent =>
              emitEvent(s3Request, httpRequest.method, userSTS.userName.value, clientIPAddress)
            case _ =>
          }
      }
      complete(httpResponse)
    }
  }

  private def processRequestForValidUser(clientIPAddress: RemoteAddress, headerIPs: HeaderIPs, httpRequest: HttpRequest, s3Request: S3Request, userSTS: User) = {
    if (isUserAuthenticated(httpRequest, userSTS.secretKey)) {
      logger.debug(s"Request authenticated: $httpRequest")

      if (isUserAuthorizedForRequest(s3Request, userSTS, clientIPAddress, headerIPs)) {
        logger.info(s"User (${userSTS.userName}) successfully authorized for request: $s3Request")

        processAuthorizedRequest(httpRequest, s3Request, userSTS, clientIPAddress)

      } else {
        logger.warn(s"User (${userSTS.userName}) not authorized for request: $s3Request")
        complete(StatusCodes.Forbidden -> AwsErrorCodes.response(StatusCodes.Forbidden))
      }
    } else {
      logger.warn(s"Request not authenticated: $httpRequest")
      complete(StatusCodes.Forbidden -> AwsErrorCodes.response(StatusCodes.Forbidden))
    }
  }
}
