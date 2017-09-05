package tech.christopherdavenport.twitterstorm

import cats.effect.{Effect, IO, Sync}
import cats.implicits._
import org.http4s.util.StreamApp
import fs2._
import org.http4s.{Request, Uri}
import org.http4s.client.blaze.PooledHttp1Client
import org.http4s.dsl.io._
import org.http4s._
import org.http4s.circe._
import org.http4s.headers._
import _root_.io.circe.syntax._
import _root_.io.circe.Decoder
import _root_.io.circe.parser._
import _root_.io.circe.{Json, JsonObject, Printer}
import org.http4s
import org.http4s.client.oauth1.{Consumer, Token, signRequest}
import pureconfig._
import tech.christopherdavenport.twitterstorm.twitter.BasicTweet

import scala.concurrent.ExecutionContext.Implicits.global

object Server extends StreamApp[IO] {

  def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, Nothing] = {
    clientStream.drain
  }

  val configStream : Stream[IO, TwitterAuthentication] = Stream.eval(
    IO(loadConfig[TwitterAuthentication]("twitterstorm").getOrElse(throw new Error("Config Error")))
  )

  val twitterStreamRequest : Request[IO] = Request[IO](
    POST,
    Uri.unsafeFromString("https://stream.twitter.com/1.1/statuses/sample.json"),
//    Uri.unsafeFromString("https://stream.twitter.com/1.1/statuses/filter.json?track=trump&stall_warnings=true"),
    headers = Headers(`Content-Type`(MediaType.`application/x-www-form-urlencoded`))
  )

  val clientStream : Stream[IO, Unit] = {
    configStream.flatMap { conf =>
      val consumerK = conf.consumerKey
      val consumerS = conf.consumerSecret
      val userK = conf.userKey
      val userS = conf.userSecret
      Stream.eval(authentication.userSign[IO](consumerK, consumerS, userK, userS)(twitterStreamRequest))
    }.flatMap( signedRequest =>
      PooledHttp1Client[IO](1).streaming(signedRequest)(resp =>
        resp.body
          .through(jsonPipe[IO])
          .through(tweetPipe)
            .filter(_.isRight)
          .observe(printSink)
          .drain
      )
    )
  }

  def jsonPipe[F[_]] : Pipe[F, Byte, Either[_root_.io.circe.ParsingFailure, Json]] = s => {
    s.through(fs2.text.utf8Decode[F]).through(fs2.text.lines[F]).map(parse)
  }

  def tweetPipe[F[_]]: Pipe[F, Either[_root_.io.circe.ParsingFailure, Json], Either[String, BasicTweet]] = {
    _.map{ either =>

      val jF : Json => Either[String, BasicTweet]  = json => {
        val e : Decoder.Result[BasicTweet] = json.as[BasicTweet]
        e.leftMap(_.message)
      }

      val eF : _root_.io.circe.ParsingFailure => Either[String, BasicTweet] = { parsingFailure =>
        Either.left(parsingFailure.message)
      }

      either.fold(
        eF, jF
      )
    }
  }

  def dropBadJsonPipe[F[_]] : Pipe[F, Either[String, BasicTweet], BasicTweet] = _.flatMap{
    case Left(_) => Stream.empty
    case Right(r) => Stream.emit(r)
  }

  def printSink[F[_]: Effect, A](implicit F: Sync[F]): Sink[F, A] = s => s.flatMap( value =>
    Stream.eval(F.delay(println(value)))
  )
}
