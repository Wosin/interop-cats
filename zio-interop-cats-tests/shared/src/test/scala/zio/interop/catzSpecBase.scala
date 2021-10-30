package zio.interop

import cats.Eq
import cats.effect.laws.util.{ TestContext, TestInstances }
import cats.implicits._
import org.scalacheck.Arbitrary
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.Configuration
import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.FunSuiteDiscipline
import zio.internal.tracing.{ Tracing, TracingConfig }
import zio.interop.catz.taskEffectInstance
import zio.{
  =!=,
  Cause,
  Clock,
  Console,
  Executor,
  IO,
  Random,
  Runtime,
  RuntimeConfig,
  System,
  Task,
  UIO,
  ZIO,
  ZManaged
}

private[zio] trait catzSpecBase
    extends AnyFunSuite
    with FunSuiteDiscipline
    with Configuration
    with TestInstances
    with catzSpecBaseLowPriority {

  type Env = Clock with Console with System with Random

  implicit def rts(implicit tc: TestContext): Runtime[Unit] = Runtime(
    (),
    RuntimeConfig
      .fromExecutor(Executor.fromExecutionContext(Int.MaxValue)(tc))
      .copy(tracing = Tracing(TracingConfig.disabled))
  )

  implicit val zioEqCauseNothing: Eq[Cause[Nothing]] = Eq.fromUniversalEquals

  implicit def zioEqIO[E: Eq, A: Eq](implicit rts: Runtime[Any], tc: TestContext): Eq[IO[E, A]] =
    Eq.by(_.either)

  implicit def zioEqTask[A: Eq](implicit rts: Runtime[Any], tc: TestContext): Eq[Task[A]] =
    Eq.by(_.either)

  implicit def zioEqUIO[A: Eq](implicit rts: Runtime[Any], tc: TestContext): Eq[UIO[A]] =
    Eq.by(uio => taskEffectInstance.toIO(uio.sandbox.either))

  implicit def zioEqZManaged[E: Eq, A: Eq](implicit rts: Runtime[Any], tc: TestContext): Eq[ZManaged[Any, E, A]] =
    Eq.by(
      zm => ZManaged.ReleaseMap.make.flatMap(releaseMap => zm.zio.provideSome[Any]((_, releaseMap)).map(_._2).either)
    )

  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet): Unit =
    checkAll(name, f(TestContext()))

}

private[interop] sealed trait catzSpecBaseLowPriority { this: catzSpecBase =>

  implicit def zioEq[R: Arbitrary, E: Eq, A: Eq](implicit rts: Runtime[Any], tc: TestContext): Eq[ZIO[R, E, A]] = {
    def run(r: R, zio: ZIO[R, E, A]) = taskEffectInstance.toIO(zio.provide(r).either)
    Eq.instance((io1, io2) => Arbitrary.arbitrary[R].sample.fold(false)(r => catsSyntaxEq(run(r, io1)) eqv run(r, io2)))
  }

  // 'R =!= Any' evidence fixes the 'diverging implicit expansion for type Arbitrary' error reproducible on scala 2.12 and 2.11.
  implicit def zmanagedEq[R, E, A](
    implicit
    @deprecated("unused", "unused") notAny: R =!= Any,
    arb: Arbitrary[R],
    eqE: Eq[E],
    eqA: Eq[A],
    rts: Runtime[Any],
    tc: TestContext
  ): Eq[ZManaged[R, E, A]] = {
    def run(r: R, zm: ZManaged[R, E, A]) =
      taskEffectInstance.toIO(
        ZManaged.ReleaseMap.make.flatMap(releaseMap => zm.zio.provide((r, releaseMap)).map(_._2).either)
      )
    Eq.instance((io1, io2) => Arbitrary.arbitrary[R].sample.fold(false)(r => catsSyntaxEq(run(r, io1)) eqv run(r, io2)))
  }

}