/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
//package concurrent

import fs2.concurrent.SignallingRef
import scala.concurrent.duration._
import fs2.internal.Token
import cats.effect._
import cats.syntax.all._

object tp {

  trait TimedPull[F[_], A] {
    type Timeout
    def uncons: Pull[F, INothing, Option[(Either[Timeout, Chunk[A]], TimedPull[F, A])]]
    def startTimer(t: FiniteDuration): Pull[F, INothing, Unit]
  }
  object TimedPull {
    def go[F[_]: Temporal, A, B](pull: TimedPull[F, A] => Pull[F, B, Unit]): Pipe[F, A, B] = { source =>
      def now = Temporal[F].monotonic

      class Timeout(val id: Token, issuedAt: FiniteDuration, d: FiniteDuration) {
        def asOfNow:  F[FiniteDuration] = now.map(now => d - (now - issuedAt))
      }

      def newTimeout(d: FiniteDuration): F[Timeout] =
        (Token[F], now).mapN(new Timeout(_, _, d))

      Stream.eval(SignallingRef[F, Option[Timeout]](None)).flatMap { time =>
        def nextAfter(t: Timeout): Stream[F, Timeout] =
          time.discrete.unNone.dropWhile(_.id == t.id).head

        // TODO is the initial time.get.unNone fine or does it spin?
        def timeouts: Stream[F, Token] =
          Stream.eval(time.get).unNone.flatMap { timeout =>
            Stream.eval(timeout.asOfNow).flatMap { t =>
              if (t <= 0.nanos) Stream.emit(timeout.id) ++ nextAfter(timeout).drain
              else Stream.sleep_[F](t)
            }
          } ++ timeouts

        def output: Stream[F, Either[Token, Chunk[A]]] =
          timeouts
            .map(_.asLeft)
            .mergeHaltR(source.chunks.map(_.asRight))
            .flatMap {
              case chunk @ Right(_) => Stream.emit(chunk)
              case timeout @ Left(id) =>
                Stream
                  .eval(time.get)
                  .collect { case Some(currentTimeout) if currentTimeout.id == id => timeout }
            }

        def toTimedPull(s: Stream[F, Either[Token, Chunk[A]]]): TimedPull[F, A] = new TimedPull[F, A] {
          type Timeout = Token

          def uncons: Pull[F, INothing, Option[(Either[Token, Chunk[A]], TimedPull[F, A])]] =
            s.pull.uncons1
              .map( _.map { case (r, next) => r -> toTimedPull(next) })

          def startTimer(t: FiniteDuration): Pull[F, INothing, Unit] = Pull.eval {
            newTimeout(t).flatMap(t => time.set(t.some))
          }
        }

        pull(toTimedPull(output)).stream
      }
    }
  }

  def groupWithin[O](s: Stream[IO, O], n: Int, t: FiniteDuration) =
    TimedPull.go[IO, O, Chunk[O]] { tp =>
      def resize(c: Chunk[O], s: Pull[IO, Chunk[O], Unit]): (Pull[IO, Chunk[O], Unit], Chunk[O]) =
        if (c.size < n) s -> c
        else {
          val (unit, rest) = c.splitAt(n)
          resize(rest, s >> Pull.output1(unit))
        }

      // Invariants:
      // acc.size < n, always
      // hasTimedOut == true iff a timeout has been received, and acc.isEmpty
      def go(acc: Chunk.Queue[O], tp: TimedPull[IO, O], hasTimedOut: Boolean = false): Pull[IO, Chunk[O], Unit] =
        tp.uncons.flatMap {
          case None =>
            Pull.output1(acc.toChunk).whenA(acc.nonEmpty)
          case Some((e, next)) =>
            e match {
              case Left(_) =>
                if (acc.nonEmpty)
                  Pull.output1(acc.toChunk) >>
                  tp.startTimer(t) >>
                  go(Chunk.Queue.empty, next)
                else
                  go(Chunk.Queue.empty, next, hasTimedOut = true)
              case Right(c) =>
                val newAcc = acc :+ c
                if (newAcc.size < n && !hasTimedOut)
                  go(newAcc, next)
                else {
                  val (toEmit, rest) = resize(newAcc.toChunk, Pull.done)
                  toEmit >> tp.startTimer(t) >> go(Chunk.Queue(rest), next)
                }
            }
        }

      tp.startTimer(t) >> go(Chunk.Queue.empty, tp)
    }.apply(s)

  import cats.effect.unsafe.implicits.global

  def ex = {
    def s(as: Int*): Stream[IO, Int] = Stream(as:_*)
    def t(d: FiniteDuration) = Stream.sleep_[IO](d)

    s(1,2,3,4,5) ++ s(6,7,8,9,10) ++ t(300.millis) ++ s(11,12,13) ++ t(200.millis) ++ s(14) ++ s(5,5,5) ++ t(600.millis) ++ s(15, 16, 17) ++ s(18, 19, 20, 21, 22) ++ t(1.second)
  }

  def t = ex.groupWithin(5, 1.second).debug().compile.toVector.unsafeRunSync()
  def tt = groupWithin(ex, 5, 1.second).debug().compile.toVector.unsafeRunSync()

  // timeout reset
  def ttt = {
    val n = System.currentTimeMillis

    {
      Stream.exec(IO(println("start"))) ++
      Stream(1,2,3) ++
      Stream.sleep_[IO](5200.millis) ++
      Stream(4,5) ++
      //  Stream(6,7) ++
      Stream.sleep_[IO](7.seconds) //++
//    Stream(8, 9)

  }
    .debugChunks(formatter = (c => s"pre $c ${System.currentTimeMillis - n}"))
    .groupWithin(3, 5.second)
    .debug(formatter = (c => s"post $c ${System.currentTimeMillis - n}"))
    .compile
    .drain
    .unsafeRunSync()
  }
  // components:
  //  a queue of chunks and timeouts
  //  a mechanism for resettable timeouts
  //  a way to avoid reacting to stale timeouts

  // problematic interleaving
  //  go is iterating and accumulating
  //  the stream enqueues a chunk
  //  the timeout enqueues
  //  go pulls a chunk
  //  go reaches limit, resets and starts another timeout, then recurs
  //  go receives stale timeout, needs to be able to discard it
  //  the state tracking the current timeout needs to be set before the next iteration of go

  // if we express timedUncons and startTimer as `F`, that guarantee is hard to achieve
  // cause they could be concurrent. We could express both as Pulls, forcing a sync loop.
  // need to add a note that the `unconsTimed` `Pull`, unlike `uncons`, is not idempotent

  // A separate concern would be to guarantee true pull-based semantics vs push-based with backpressure at 1
  // def prog =
  //   (MiniKafka.create[F], Queue.synchronous[F, Unit], SignallingRef[F, Unit](())).mapN {
  //     (kafka, q, sig) =>
  //     def consumer = Stream.repeatEval(sig.set(()) >> q.dequeue1).evalMap(_ => kafka.commit)
  //     def producer = sig.discrete.zipRight(Stream.repeatEval(kafka.poll)).through(q.enqueue)

  //     consumer
  //       .concurrently(producer)
  //       .compile
  //       .drain
  //   }.flatten

}
