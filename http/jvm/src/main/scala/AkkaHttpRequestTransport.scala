package covenant.http

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{HttpHeader => AkkaHttpHeader, _}
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.http.scaladsl.unmarshalling.{Unmarshal, _}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteStringBuilder
import monix.eval.Task
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import monix.reactive.subjects.PublishSubject
import sloth._

import scala.concurrent.Future
import scala.util.{Failure, Success}

object AkkaHttpRequestTransport {
  def apply[PickleType](baseUri: String)(implicit
    scheduler: Scheduler,
    system: ActorSystem,
    asText: AsTextMessage[PickleType],
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): HttpRequestTransport[PickleType] = HttpRequestTransport(

    (request, headers) => sendRequest(baseUri, request, toAkkaHeaders(headers)),
    (request, headers) => sendStreamRequest(baseUri, request, toAkkaHeaders(headers))
  )

  private def toAkkaHeaders(headers: List[HttpHeader]) = headers.flatMap { h =>
    AkkaHttpHeader.parse(name = h.name, value = h.value) match {
      case ParsingResult.Ok(header, _) => Some(header)
      case ParsingResult.Error(err) =>
        scribe.warn(s"Error parsing http header ($h), will ignore this header: $err")
        None
    }
  }

  // TODO: unify both send methods and branch in response?
  private def sendRequest[PickleType](baseUri: String, request: Request[PickleType], headers: List[AkkaHttpHeader])(implicit
    scheduler: Scheduler,
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Task[Either[HttpErrorCode, PickleType]] = Task.deferFuture {

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]

    entity.flatMap { entity =>
      Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = headers, entity = entity))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              response.entity.dataBytes.runFold(new ByteStringBuilder)(_ append _).flatMap { b =>
                Unmarshal(b.result).to[PickleType]
              }.map(Right.apply)
            case code =>
              response.discardEntityBytes() // we are not going to read the entity.dataBytes, therefore discard explicitly.
              Future.successful(Left(HttpErrorCode(code.intValue, code.reason)))
          }
        }
    }
  }

  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType], headers: List[AkkaHttpHeader])(implicit
    scheduler: Scheduler,
    system: ActorSystem,
    materializer: ActorMaterializer,
    asText: AsTextMessage[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Task[Either[HttpErrorCode, Observable[PickleType]]] = Task.deferFuture {

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]

    entity.flatMap { entity =>
      val requested: Future[Either[HttpErrorCode, Source[ServerSentEvent, NotUsed]]] = Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = headers.toList, entity = entity))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              Unmarshal(response).to[Source[ServerSentEvent, NotUsed]].map(Right.apply)
            case code =>
              response.discardEntityBytes()
              Future.successful(Left(HttpErrorCode(code.intValue, code.reason)))
          }
        }

      requested.map(_.map { source =>
        val subject = PublishSubject[PickleType]()
        val connectObservable = ConnectableObservable.cacheUntilConnect(source = subject, subject = PublishSubject[PickleType])

        source.runFoldAsync[Future[Ack]](Ack.Continue) { (_, value) =>
          val pickled = asText.read(value.data)
          subject.onNext(pickled)
        }.onComplete {
          case Success(_) => subject.onComplete()
          case Failure(err) => subject.onError(err)
        }

        connectObservable.doAfterSubscribe { () =>
          connectObservable.connect()
          ()
        }
      })
    }
  }
}
