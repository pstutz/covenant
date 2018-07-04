package covenant.http

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import covenant._
import covenant.api._
import covenant.http.api._
import covenant.util.StopWatch
import monix.execution.Scheduler
import sloth._

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}

case class HttpServerConfig(bufferSize: Int = 100, overflowStrategy: OverflowStrategy = OverflowStrategy.fail, keepAliveInterval: FiniteDuration = 30 seconds)

object AkkaHttpRoute {
   import covenant.util.LogHelper._

//   def fromApiRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, Event, ErrorType, State](
//     router: Router[PickleType, RawServerDsl.ApiFunction[Event, State, ?]],
//     api: HttpApiConfiguration[Event, ErrorType, State])(implicit
//     scheduler: Scheduler): Route = {
//
//     requestFunctionToRouteWithHeaders[PickleType] { (r, httpRequest) =>
//       val watch = StopWatch.started
//       val state: Future[State] = api.requestToState(httpRequest)
//       val path = httpRequest.getUri.toString.split("/").toList //TODO
//
//       router(r) match {
//         case RouterResult.Success(arguments, apiFunction) =>
//           val apiResponse = apiFunction.run(state)
//
//           val returnValue = {
//             apiResponse.value match {
//               case RequestResponse.Single(task) =>
////                 val rawResult = future.map(_.raw)
////                 val serializedResult = future.map(_.serialized)
////                 scribe.info(s"http -->[response] ${requestLogLine(path, arguments, rawResult)} / ${events}. Took ${watch.readHuman}.")
//                 ??? //TODO
//               case RequestResponse.Stream(observable) =>
//                 ??? //TODO
//             }
//
//             //TODO: what about private evnets? scoping?
////             api.publishEvents(apiResponse.action.events)
//
////             serializedResult.transform {
////               case Success(v) => Success(complete(v))
////               //TODO map errors
////               case Failure(err) => Success(complete(StatusCodes.BadRequest -> err.toString))
////             }
//           }
//
////           Right(returnValue)
//           ???
//
//         case RouterResult.Failure(arguments, error) =>
//           scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
//           Left(error)
//       }
//     }
//   }

  def fromRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller : AsTextMessage](
    router: Router[PickleType, RequestResponse[HttpErrorCode, ?]],
    config: HttpServerConfig = HttpServerConfig(),
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode] = PartialFunction.empty
  )(implicit scheduler: Scheduler): Route = responseRouterToRoute[PickleType](router, config, recoverServerFailure, recoverThrowable)

  //TODO split code, share with/without headers
  private def responseRouterToRoute[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](
    router: Router[PickleType, RequestResponse[HttpErrorCode, ?]],
    config: HttpServerConfig,
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode],
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        val path = pathRest.split("/").toList
        entity(as[PickleType]) { entity =>
          router(Request(path, entity)).toEither match {
            case Right(result) => result match {
              //TODO dupe
              case RequestResponse.Single(task) => onComplete(task.runAsync) {
                case Success(r) => r match {
                  case Right(v) => complete(v)
                  case Left(e) => complete(StatusCodes.custom(e.code, e.message))
                }
                case Failure(e) =>
                  val error = recoverThrowable.lift(e)
                    .fold[StatusCode](StatusCodes.InternalServerError)(e => StatusCodes.custom(e.code, e.message))
                  complete(error)
              }
              case RequestResponse.Stream(task) => onComplete(task.runAsync) {
                case Success(r) => r match {
                  case Right(observable) =>
                    val source = Source.fromPublisher(observable.map(t => ServerSentEvent(asText.write(t))).toReactivePublisher)
                    complete(source.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
                  case Left(e) => complete(StatusCodes.custom(e.code, e.message))
                }
                case Failure(e) =>
                  val error = recoverThrowable.lift(e)
                    .fold[StatusCode](StatusCodes.InternalServerError)(e => StatusCodes.custom(e.code, e.message))
                  complete(error)
              }
            }
            case Left(e) =>
              val error = recoverServerFailure.lift(e)
                .fold[StatusCode](StatusCodes.BadRequest)(e => StatusCodes.custom(e.code, e.message))
              complete(error)
          }
        }
      }
    }
  }

  //TODO non-state dependent actions will still trigger the state future
  private def requestFunctionToRouteWithHeaders[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](router: (Request[PickleType], HttpRequest) => Either[ServerFailure, Future[Route]]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        extractRequest { request =>
          val path = pathRest.split("/").toList
          entity(as[PickleType]) { entity =>
            router(Request(path, entity), request) match {
              case Right(result) => onComplete(result) {
                case Success(r) => r
                case Failure(e) => complete(StatusCodes.InternalServerError -> e.toString)
              }
              case Left(err) => complete(StatusCodes.BadRequest -> err.toString)
            }
          }
        }
      }
    }
  }
}
