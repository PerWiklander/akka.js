package org.scalatest.enablers

import java.util.concurrent.TimeUnit

import org.scalactic.source
import org.scalatest.Suite.anExceptionThatShouldCauseAnAbort
import org.scalatest.Resources
import org.scalatest.exceptions.{StackDepthException, TestFailedDueToTimeoutException, TestPendingException}
import org.scalatest.time.{Nanosecond, Nanoseconds, Span}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import scala.annotation.tailrec
import scala.language.higherKinds

trait Retrying[T] {

  def retry(timeout: Span, interval: Span, pos: source.Position)(fun: => T): T

}

object Retrying {

  implicit def retryingNatureOfFutureT[T](implicit execCtx: ExecutionContext): Retrying[Future[T]] =
    new Retrying[Future[T]] {
      def retry(timeout: Span, interval: Span, pos: source.Position)(fun: => Future[T]): Future[T] = {
        val startNanos = System.nanoTime

        val initialInterval = Span(interval.totalNanos * 0.1, Nanoseconds) // config.interval scaledBy 0.1

        // Can't make this tail recursive. TODO: Document that fact.
        def tryTryAgain(attempt: Int): Future[T] = {
          fun recoverWith {

            case tpe: TestPendingException => Future.failed(tpe)

            case e: Throwable if !anExceptionThatShouldCauseAnAbort(e) =>

              // Here I want to try again after the duration. So first calculate the duration to
              // wait before retrying. This is front loaded with a simple backoff algo.
              val duration = System.nanoTime - startNanos
              if (duration < timeout.totalNanos) {
                val chillTime =
                  if (duration < interval.totalNanos) // For first interval, we wake up every 1/10 of the interval.  This is mainly for optimization purpose.
                    initialInterval.millisPart
                  else
                    interval.millisPart

                // Create a Promise
                val promise = Promise[T]

                val task =
                  Future {
                    val newFut = tryTryAgain(attempt + 1)
                    newFut onComplete {
                      case Success(res) => promise.success(res)
                      case Failure(ex) => promise.failure(ex)
                    }
                  }


                import scala.concurrent.duration._
                akka.testkit.Await.result(task, chillTime millis)
                // scheduler.schedule(task, chillTime, TimeUnit.MILLISECONDS)
                promise.future
              }
              else { // Timed out so return a failed Future
                val durationSpan = Span(1, Nanosecond) scaledBy duration // Use scaledBy to get pretty units
                Future.failed(
                  new TestFailedDueToTimeoutException(
                    (_: StackDepthException) =>
                      Some(
                        if (e.getMessage == null)
                          attempt.toString
                          // Resources.didNotUltimatelySucceed(attempt.toString, durationSpan.prettyString)
                        else
                          e.getMessage + e.getMessage
                          // Resources.didNotUltimatelySucceedBecause(attempt.toString, durationSpan.prettyString, e.getMessage)
                      ),
                    Some(e),
                    Left(pos),
                    None,
                    timeout
                  )
                )
              }
          }
        }
        tryTryAgain(1)
      }
    }

  /**
    * Provides implicit <code>Retrying</code> implementation for <code>T</code>.
    */
  implicit def retryingNatureOfT[T]: Retrying[T] =
    new Retrying[T] {
      def retry(timeout: Span, interval: Span, pos: source.Position)(fun: => T): T = {
        val startNanos = System.nanoTime
        def makeAValiantAttempt(): Either[Throwable, T] = {
          try {
            Right(fun)
          }
          catch {
            case tpe: TestPendingException => throw tpe
            case e: Throwable if !anExceptionThatShouldCauseAnAbort(e) => Left(e)
          }
        }

        val initialInterval = Span(interval.totalNanos * 0.1, Nanoseconds) // config.interval scaledBy 0.1

        @tailrec
        def tryTryAgain(attempt: Int): T = {
          makeAValiantAttempt() match {
            case Right(result) => result
            case Left(e) =>
              val duration = System.nanoTime - startNanos
              if (duration < timeout.totalNanos) {
                // if (duration < interval.totalNanos) // For first interval, we wake up every 1/10 of the interval.  This is mainly for optimization purpose.
                //   Thread.sleep(initialInterval.millisPart, initialInterval.nanosPart)
                // else
                //   Thread.sleep(interval.millisPart, interval.nanosPart)
              }
              else {
                val durationSpan = Span(1, Nanosecond) scaledBy duration // Use scaledBy to get pretty units
                throw new TestFailedDueToTimeoutException(
                  (_: StackDepthException) =>
                    Some(
                      if (e.getMessage == null)
                        Resources.didNotEventuallySucceed(attempt.toString, durationSpan.prettyString)
                      else
                        Resources.didNotEventuallySucceedBecause(attempt.toString, durationSpan.prettyString, e.getMessage)
                    ),
                  Some(e),
                  Left(pos),
                  None,
                  timeout
                )
              }

              tryTryAgain(attempt + 1)
          }
        }
        tryTryAgain(1)
      }
    }

}
