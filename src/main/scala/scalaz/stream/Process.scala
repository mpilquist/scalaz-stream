package scalaz.stream

import scala.collection.immutable.IndexedSeq

import scalaz.{Catchable,Monad,MonadPlus,Nondeterminism}
import scalaz.concurrent.Task
import scalaz.Leibniz.===
import scalaz.{\/,-\/,\/-,~>,Leibniz}
import \/._

/** 
 * A `Process[F,O]` represents a stream of `O` values which can interleave 
 * external requests to evaluate expressions of the form `F[A]`. It takes
 * the form of a state machine with three possible states: `Emit`, which 
 * indicates that `h` should be emitted to the output stream, `Halt`,
 * which indicates that the `Process` is finished making requests and 
 * emitting values to the output stream, and `Await` which asks the driver 
 * to evaluate some `F[A]` and resume processing once the result is available. 
 * See the constructor definitions in the `Process` companion object.
 */
trait Process[+F[_],+O] {
  
  import Process._

  /** Transforms the output values of this `Process` using `f`. */
  final def map[O2](f: O => O2): Process[F,O2] = this match {
    case Await(req,recv,fb,c) => 
      Await[F,Any,O2](req, recv andThen (_ map f), fb map f, c map f) 
    case Emit(h, t) => Emit[F,O2](h map f, t map f)
    case Halt => Halt
  }

  /** 
   * Generate a `Process` dynamically for each output of this `Process`, and
   * sequence these processes using `append`. 
   */
  final def flatMap[F2[x]>:F[x], O2](f: O => Process[F2,O2]): Process[F2,O2] = this match {
    case Halt => Halt
    case Emit(o, t) => 
      if (o.isEmpty) t.flatMap(f)
      else f(o.head) ++ emitAll(o.tail, t).flatMap(f)
    case Await(req,recv,fb,c) => 
      Await(req, recv andThen (_ flatMap f), fb flatMap f, c flatMap f)
  }
  
  /** Run this `Process`, then, if it halts without an error, run `p2`. */
  final def append[F2[x]>:F[x], O2>:O](p2: => Process[F2,O2]): Process[F2,O2] = this match {
    case Halt => p2
    case Emit(h, t) => emitAll(h, t append p2)
    case Await(req,recv,fb,c) => 
      Await(req, recv andThen (_ append p2), fb append p2, c)
  }

  /** Operator alias for `append`. */
  final def ++[F2[x]>:F[x], O2>:O](p2: => Process[F2,O2]): Process[F2,O2] = 
    this append p2

  /** 
   * Removes one layer of emitted elements from this `Process`,
   * if this `Process` does not begin with an `Emit`, returns the empty
   * sequence along with `this`. Useful when defining certain operations. 
   */
  final def unemit: (Seq[O], Process[F,O]) = this match {
    case Emit(h,t) => (h, t)
    case _ => (Seq(), this) 
  }

  /** 
   * Run this process until it halts, then run it again and again, as
   * long as no errors occurt. 
   */
  final def repeat[F2[x]>:F[x],O2>:O]: Process[F2,O2] = {
    def go(cur: Process[F,O]): Process[F,O] = cur match {
      case Halt => go(this)
      case Await(req,recv,fb,c) => Await(req, recv andThen go, fb, c)
      case Emit(h, t) => emitAll(h, go(t))
    }
    go(this)
  }

  /**
   * Halt this process, but give it an opportunity to run any requests it has 
   * in the `cleanup` argument of its next `Await`.
   */
  @annotation.tailrec
  final def kill: Process[F,Nothing] = this match {
    case Await(req,recv,fb,c) => c.drain 
    case Halt => Halt
    case Emit(h, t) => t.kill
  }

  /**
   * Ignores output of this `Process`. A drained `Process` will never `Emit`.  
   */
  def drain: Process[F,Nothing] = this match {
    case Halt => Halt
    case Emit(h, t) => t.drain
    case Await(req,recv,fb,c) => Await(
      req, recv andThen (_ drain), 
      fb.drain, c.drain)
  }

  /** 
   * Feed the output of this `Process` as input of `p2`. The implementation  
   * will fuse the two processes, so this process will only generate
   * values as they are demanded by `p2`. If `p2` signals termination, `this`
   * is killed using `kill`, giving it the opportunity to clean up. 
   */
  final def pipe[O2](p2: Process1[O,O2]): Process[F,O2] =
    (this tee Halt)(p2) // awesome - p2 will never await on the left

  /** Operator alias for `pipe`. */
  final def |>[O2](p2: Process1[O,O2]): Process[F,O2] = 
    this pipe p2
  
  final def tee[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(t: Tee[O,O2,O3]): Process[F2,O3] = 
    (this wye p2)(t)(null)

  /* 
   * Use a `Tee` to interleave or combine the outputs of `this` and
   * `p2`. This can be used for zipping, interleaving, and so forth.
   * Nothing requires that the `Tee` read elements from each 
   * `Process` in lockstep. It could read fifty elements from one 
   * side, then two elements from the other, then combine or
   * interleave these values in some way, etc.
   * 
   * The definition uses two helper functions, `feedL` and `feedR`,
   * which feed the `Tee` in a tail-recursive loop as long as
   * it is awaiting input from either side.
   */ 
  final def wye[F2[x]>:F[x],O2,O3](p2: Process[F2,O2])(t: Wye[O,O2,O3])(implicit F: Nondeterminism[F2]): Process[F2,O3] = {
    @annotation.tailrec
    def feedL(emit: Seq[O], tail: Process[F,O], 
              other: Process[F2,O2],
              recv: O => Wye[O,O2,O3], 
              fb: Wye[O,O2,O3],
              c: Wye[O,O2,O3]): Process[F2,O3] = 
      if (emit isEmpty) (tail wye other)(Await(L[O], recv, fb, c))
      else recv(emit.head) match {
        case t2@Await(e, recv2, fb2, c2) => (e.tag: @annotation.switch) match {
          case 0 => 
            feedL(emit.tail, tail, other, recv2, fb2, c2)
          case _ => (Emit(emit.tail, tail) wye other)(t2)
        }
        case p => (Emit(emit.tail, tail) wye other)(p)
      }
    @annotation.tailrec
    def feedR(emit: Seq[O2], tail: Process[F2,O2], 
              other: Process[F,O],
              recv: O2 => Wye[O,O2,O3], 
              fb: Wye[O,O2,O3],
              c: Wye[O,O2,O3]): Process[F2,O3] = 
      if (emit isEmpty) (other wye tail)(Await(R[O2], recv, fb, c))
      else recv(emit.head) match {
        case t2@Await(e, recv2, fb2, c2) => (e.tag: @annotation.switch) match {
          case 1 => feedR(emit.tail, tail, other, recv2, fb2, c2)
          case _ => (other wye Emit(emit.tail, tail))(t2)
        }
        case p => (other wye Emit(emit.tail, tail))(p)
      }
    // we round 
    def feedBoth(
              emit1: Seq[O], tail1: Process[F,O], 
              emit2: Seq[O2], tail2: Process[F2,O2], 
              recv: These[O,O2] => Wye[O,O2,O3], 
              fb: Wye[O,O2,O3],
              c: Wye[O,O2,O3]): Process[F2,O3] = {
      var curRecv = recv 
      var curFb = fb
      var curC = c
      var emit1Cur: Seq[O] = emit1
      var emit2Cur: Seq[O2] = emit2
      while (!emit1Cur.isEmpty && emit2Cur.isEmpty) {
        recv(These.Both(emit1Cur.head, emit2Cur.head)) match {
          case t2@Await(e,recv2,fb2,c2) => (e.tag: @annotation.switch) match {
            case 2 => emit1Cur = emit1Cur.tail; emit2Cur = emit2Cur.tail 
                      curRecv = recv2; 
                      curFb = fb2
                      curC = c2
            case _ => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t2) 
          }
          case t => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t) 
        } 
      }
      while (!emit1Cur.isEmpty) {
        recv(These.This(emit1Cur.head)) match {
          case t2@Await(e,recv2,fb2,c2) => (e.tag: @annotation.switch) match {
            case 2 => emit1Cur = emit1Cur.tail
                      curRecv = recv2
                      curFb = fb2
                      curC = c2
            case _ => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t2) 
          }
          case t => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t) 
        }
      }
      while (!emit2Cur.isEmpty) {
        recv(These.That(emit2Cur.head)) match {
          case t2@Await(e,recv2,fb2,c2) => (e.tag: @annotation.switch) match {
            case 2 => emit2Cur = emit2Cur.tail
                      curRecv = recv2
                      curFb = fb2
                      curC = c2
            case _ => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t2) 
          }
          case t => return (Emit(emit1Cur, tail1) wye Emit(emit2Cur, tail2))(t) 
        }
      }
      (tail1 wye tail2)(Await(Both[O,O2], curRecv, curFb, curC))
    }
    // NB: lots of casts needed here because Scala pattern matching does not 
    // properly refine types; my attempts at manually adding type equality 
    // witnesses also failed; this actually worked better in 2.9.2
    t match {
      case Halt => this.kill ++ p2.kill
      case Emit(h,t) => Emit(h, (this wye p2)(t))
      case Await(side, recv, fb, c) => (side.tag: @annotation.switch) match {
        case 0 => this match { // Left
          case Halt => p2.kill
          case Emit(o,ot) => 
            feedL(o.asInstanceOf[Seq[O]], ot.asInstanceOf[Process[F,O]], p2, 
                  recv.asInstanceOf[O => Wye[O,O2,O3]],
                  fb.asInstanceOf[Wye[O,O2,O3]], c.asInstanceOf[Wye[O,O2,O3]]) 
          case Await(reqL, recvL, fbL, cL) => 
            Await(reqL, recvL andThen (pnext => (pnext wye p2)(t)), 
                  (fbL wye p2)(t), (cL wye p2)(t))
        }
        case 1 => p2 match { // Right
          case Halt => this.kill ++ Halt
          case Emit(o,ot) => 
            feedR(o.asInstanceOf[Seq[O2]], ot.asInstanceOf[Process[F2,O2]], this, 
                  recv.asInstanceOf[O2 => Wye[O,O2,O3]], 
                  fb.asInstanceOf[Wye[O,O2,O3]], c.asInstanceOf[Wye[O,O2,O3]])
          case Await(reqR, recvR, fbR, cR) => 
            Await(reqR.asInstanceOf[F2[Int]], // really should be existential 
                  recvR.asInstanceOf[Int => Process[F2,O2]] andThen (p3 => (this wye p3)(t)), 
                  (this wye fbR.asInstanceOf[Process[F2,O2]])(t), 
                  (this wye cR.asInstanceOf[Process[F2,O2]])(t))
        }
        case 2 => // Both 
          val u1 = this.unemit; val h1 = u1._1; val t1 = u1._2
          val u2 = p2.unemit; val h2 = u2._1; val t2 = u2._2
          if (h1.isEmpty && h2.isEmpty) { 
            // We have no elements queued up on either side
            // There are two cases: 
            //   * If either side has halted, we detach the Wye from 
            //     that side and use `|>` to feed it the remainder
            //     of the side that lives.
            //   * If both sides are in the Await state, we use the
            //     Nondeterminism instance to request both sides
            //     concurrently.
            // more casts required due to broken pattern matching
            this match {
              case Halt => p2 |> t.detachL 
              case Await(reqL, recvL, fbL, cL) => p2 match {
                case Halt => this |> t.detachR 
                case Await(reqR, recvR, fbR, cR) => 
                  val reqL_ = reqL.asInstanceOf[F[String]]
                  val recvL_ = recvL.asInstanceOf[String => Process[F,O]]
                  val reqR_ = reqR.asInstanceOf[F2[Int]] // making up some types here just to avoid confusion
                  val recvR_ = recvR.asInstanceOf[Int => Process[F2,O2]]
                  val fbR_ = fbR.asInstanceOf[Process[F2,O2]]
                  val cR_ = fbR.asInstanceOf[Process[F2,O2]]
                  emit(F.choose(reqL_, reqR_)).eval.flatMap {
                    case Left((l,reqR2)) => 
                      (recvL_(l) wye Await(reqR2, recvR_, fbR_, cR_))(t)
                    case Right((reqL2,r)) => 
                      (Await(reqL2, recvL_, fbL, cL) wye recvR_(r))(t)
                  }
              }
            }
          }
          else feedBoth(
                h1, t1, h2, t2.asInstanceOf[Process[F2,O2]],
                recv.asInstanceOf[These[O,O2] => Wye[O,O2,O3]],
                fb.asInstanceOf[Wye[O,O2,O3]],
                c.asInstanceOf[Wye[O,O2,O3]])
      }
    }
  }

  /** Translate the request type from `F` to `G`, using the given polymorphic function. */
  def translate[G[_]](f: F ~> G): Process[G,O] = this match {
    case Emit(h, t) => Emit(h, t.translate(f))
    case Halt => Halt
    case Await(req, recv, fb, c) => 
      Await(f(req), recv andThen (_ translate f), fb translate f, c translate f)
  }

  /** 
   * Map over this `Process` to produce a stream of `F`-actions, 
   * then evaluate these actions. 
   */
  def evalMap[F2[x]>:F[x],O2](f: O => F2[O2]): Process[F2,O2] = 
    map(f).eval
 
  /** 
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety. 
   */
  final def collect[F2[x]>:F[x], O2>:O](implicit F: Monad[F2], C: Catchable[F2]): F2[IndexedSeq[O2]] = {
    def go(cur: Process[F2,O2], acc: IndexedSeq[O2]): F2[IndexedSeq[O2]] = 
      cur match {
        case Emit(h,t) => go(t.asInstanceOf[Process[F2,O2]], acc ++ h.asInstanceOf[Seq[O2]]) 
        case Halt => F.point(acc)
        case Await(req,recv,fb,c) => 
           F.bind (C.attempt(req.asInstanceOf[F2[Int]])) {
             case Left(End) => go(fb.asInstanceOf[Process[F2,O2]], acc)
             case Left(err) => 
               go(c.asInstanceOf[Process[F2,O2]] ++ await[F2,Nothing,O2](C.fail(err))(), acc)
             case Right(o) => go(recv.asInstanceOf[Int => Process[F2,O2]](o), acc)
           }
      }
    go(this, IndexedSeq())
  }

  /** Run this `Process`, purely for its effects. */
  final def run[F2[x]>:F[x]](implicit F: Monad[F2], C: Catchable[F2]): F2[Unit] = 
    F.void(drain.collect(F, C))

  /** Skips any output elements not matching the predicate. */
  def filter(f: O => Boolean): Process[F,O] = 
    this |> Process.filter(f)

  /** Halts this `Process` after emitting `n` elements. */
  def take(n: Int): Process[F,O] = 
    this |> Process.take[O](n)

  /** Halts this `Process` as soon as the predicate tests false. */
  def takeWhile(f: O => Boolean): Process[F,O] = 
    this |> Process.takeWhile(f)

  /** Ignores the first `n` elements output from this `Process`. */
  def drop(n: Int): Process[F,O] = 
    this |> Process.drop[O](n)

  /** Ignores elements from the output of this `Process` until `f` tests false. */
  def dropWhile(f: O => Boolean): Process[F,O] = 
    this |> Process.dropWhile(f)
}

object Process {
  case class Await[F[_],A,+O] private[stream](
    req: F[A], recv: A => Process[F,O],
    fallback: Process[F,O] = Halt,
    cleanup: Process[F,O] = Halt) extends Process[F,O]

  case class Emit[F[_],O] private[stream](
    head: Seq[O], 
    tail: Process[F,O]) extends Process[F,O]

  case object Halt extends Process[Nothing,Nothing]

  def emitAll[F[_],O](
      head: Seq[O], 
      tail: Process[F,O] = Halt): Process[F,O] = 
    tail match {
      case Emit(h2,t) => Emit(head ++ h2.asInstanceOf[Seq[O]], t.asInstanceOf[Process[F,O]])
      case _ => Emit(head, tail)
    }
  def emit[F[_],O](
      head: O, 
      tail: Process[F,O] = Halt): Process[F,O] = 
    emitAll(Stream(head), tail)

  def await[F[_],A,O](req: F[A])(
      recv: A => Process[F,O] = (a: A) => Halt, 
      fallback: Process[F,O] = Halt,
      cleanup: Process[F,O] = Halt): Process[F,O] = 
    Await(req, recv, fallback, cleanup)

  implicit def processInstance[F[_]]: MonadPlus[({type f[x] = Process[F,x]})#f] = 
  new MonadPlus[({type f[x] = Process[F,x]})#f] {
    def empty[A] = Halt
    def plus[A](a: Process[F,A], b: => Process[F,A]): Process[F,A] = 
      a ++ b
    def point[A](a: => A): Process[F,A] = emit(a)
    def bind[A,B](a: Process[F,A])(f: A => Process[F,B]): Process[F,B] = 
      a flatMap f
  }

  /* Special exception indicating normal termination */
  case object End extends scala.util.control.ControlThrowable {
    override def fillInStackTrace = this 
  }
 
  /** 
   * A simple tail recursive function to collect all the output of a 
   * `Process[Task,O]`. Because `Task` has a `run` function,
   * we can implement this as a tail-recursive function. 
   */
  def collectTask[O](src: Process[Task,O]): IndexedSeq[O] = {
    @annotation.tailrec
    def go(cur: Process[Task,O], acc: IndexedSeq[O]): IndexedSeq[O] = 
      cur match {
        case Emit(h,t) => go(t, acc ++ h) 
        case Halt => acc
        case Await(req,recv,fb,err) =>
          val next = 
            try recv(req.run)
            catch { 
              case End => fb // Normal termination
              case e: Exception => err ++ failTask(e) // Helper function, defined below
            }
          go(next, acc)
      }
    go(src, IndexedSeq()) 
  }

  def failTask[O](e: Throwable): Process[Task,O] = 
    await[Task,O,O](Task(throw e))()

  /** Prefix syntax for `p.repeat`. */
  def repeat[F[_],O](p: Process[F,O]): Process[F,O] = p.repeat

  /* 
   * Generic combinator for producing a `Process[Task,O]` from some
   * effectful `O` source. The source is tied to some resource,
   * `R` (like a file handle) that we want to ensure is released.
   * See `lines` below for an example use. 
   */
  def resource[R,O](acquire: Task[R])(
                    release: R => Task[Unit])(
                    step: R => Task[O]): Process[Task,O] = {
    def go(step: Task[O], onExit: Task[Unit]): Process[Task,O] =
      await[Task,O,O](step) ( 
        o => emit(o, go(step, onExit)) // Emit the value and repeat 
      , await[Task,Unit,O](onExit)()  // Release resource when exhausted
      , await[Task,Unit,O](onExit)()) // or in event of error
    await(acquire) ( r => go(step(r), release(r)), Halt, Halt )
  }

  case class Two[-I,-I2]() {
    sealed trait Y[-X] { def tag: Int }
    sealed trait T[-X] extends Y[X] 
    sealed trait Is[-X] extends T[X]
    case object Left extends Is[I] { def tag = 0 }
    case object Right extends T[I2] { def tag = 1 }  
    case object Both extends Y[These[I,I2]] { def tag = 2 }
  }

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])

  type Process1[-I,+O] = Process[Two[I,Any]#Is, O]
  type Tee[-I,-I2,+O] = Process[Two[I,I2]#T, O]
  type Wye[-I,-I2,+O] = Process[Two[I,I2]#Y, O]

  object Subtyping {
    def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1 
    def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t 
  }

  // a failed attempt to work around Scala's broken type refinement in 
  // pattern matching by supplying the equality witnesses manually

  /** Obtain an equality witness from an `Is` request. */
  def witnessIs[I,J](req: Two[I,Nothing]#Is[J]): I === J = 
    Leibniz.refl[I].asInstanceOf[I === J]

  /** Obtain an equality witness from a `T` request. */
  def witnessT[I,I2,J](t: Two[I,I2]#T[J]): 
  (I === J) \/ (I2 === J) = 
    if (t.tag == 0) left(Leibniz.refl[I].asInstanceOf[I === J])
    else right(Leibniz.refl[I2].asInstanceOf[I2 === J])

  /** Obtain an equality witness from a `Y` request. */
  def witnessY[I,I2,J](t: Two[I,I2]#Y[J]): 
  (I === J) \/ (I2 === J) \/ (These[I,I2] === J) = 
    if (t.tag == 2) right(Leibniz.refl[I].asInstanceOf[These[I,I2] === J])
    else left(witnessT(t.asInstanceOf[Two[I,I2]#T[J]]))

  private val Left_ = Two[Any,Any]().Left
  private val Right_ = Two[Any,Any]().Left
  private val Both_ = Two[Any,Any]().Both

  def Get[I]: Two[I,Any]#Is[I] = Left_ 
  def L[I]: Two[I,Any]#Is[I] = Left_ 
  def R[I2]: Two[Any,I2]#T[I2] = Right_  
  def Both[I,I2]: Two[I,I2]#Y[These[I,I2]] = Both_ 

  def await1[I]: Process1[I,I] = 
    Await(Get[I], (i: I) => emit1(i).asInstanceOf[Process1[I,I]], Halt, Halt)

  def receive1[I,O](recv: I => Process1[I,O], fallback: Process1[I,O] = Halt): Process1[I,O] = 
    Await(Get[I], recv, fallback, Halt)

  def emit1[O](h: O): Process1[Any,O] = emit(h, Halt)
  
  def emitAll1[O](h: Seq[O]): Process1[Any,O] = 
    emitAll(h)

  /** Repeatedly echo the input; satisfies `x |> id == x` and `id |> x == x`. */
  def id[I]: Process1[I,I] = 
    await1[I].repeat

  /** Transform the input using the given function, `f`. */
  def lift[I,O](f: I => O): Process1[I,O] = 
    id[I] map f
  
  /** Skips any elements of the input not matching the predicate. */
  def filter[I](f: I => Boolean): Process1[I,I] =
    await1[I] flatMap (i => if (f(i)) emit1(i) else Halt) repeat

  /** Passes through `n` elements of the input, then halt. */
  def take[I](n: Int): Process1[I,I] = 
    if (n <= 0) Halt
    else await1[I] ++ take(n-1)

  /** Passes through elements of the input as long as the predicate is true, then halt. */
  def takeWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) emit1(i) ++ takeWhile(f) else Halt)

  /** 
   * Skips elements of the input while the predicate is true, 
   * then passes through the remaining inputs. 
   */
  def dropWhile[I](f: I => Boolean): Process1[I,I] = 
    await1[I] flatMap (i => if (f(i)) dropWhile(f) else id)

  /** Reads a single element of the input, emits nothing, then halts. */
  def skip: Process1[Any,Nothing] = await1[Any].flatMap(_ => Halt) 

  /** Skips the first `n` elements of the input, then passes through the rest. */
  def drop[I](n: Int): Process1[I,I] = 
    skip.replicateM_(n).drain ++ id[I]

                          /*                       

  We sometimes need to construct a `Process` that will pull values
  from multiple input sources. For instance, suppose we want to 
  'zip' together two files, `f1.txt` and `f2.txt`, combining
  corresponding lines in some way. Using the same trick we used for
  `Process1`, we can create a two-input `Process` which can request
  values from either the 'left' stream or the 'right' stream. We'll
  call this a `Tee`, after the letter 'T', which looks like a 
  little diagram of two inputs being combined into one output. 

                           */

  /* Again some helper functions to improve type inference. */

  def awaitL[I]: Tee[I,Any,I] = 
    receiveL[I,Any,I](emitT)

  def awaitR[I2]: Tee[Any,I2,I2] = 
    receiveR[Any,I2,I2](emitT)

  def receiveL[I,I2,O](
      recv: I => Tee[I,I2,O], 
      fallback: Tee[I,I2,O] = Halt,
      cleanup: Tee[I,I2,O] = Halt): Tee[I,I2,O] = 
    await[Two[I,I2]#T,I,O](L)(recv, fallback, cleanup)

  def receiveR[I,I2,O](
      recv: I2 => Tee[I,I2,O], 
      fallback: Tee[I,I2,O] = Halt,
      cleanup: Tee[I,I2,O] = Halt): Tee[I,I2,O] = 
    await[Two[I,I2]#T,I2,O](R)(recv, fallback, cleanup)

  def emitT[O](h: O): Tee[Any,Any,O] = 
    emit(h)
  
  def emitAllT[O](h: Seq[O]): Tee[Any,Any,O] = 
    emitAll(h, Halt)

  def zipWith[I,I2,O](f: (I,I2) => O): Tee[I,I2,O] = { for {
    i <- awaitL[I]
    i2 <- awaitR[I2]
    r <- emitT(f(i,i2))
  } yield r } repeat

  def zip[I,I2]: Tee[I,I2,(I,I2)] = zipWith((_,_))

  /* 
   * Like `zip` on lists, the above version halts as soon as either
   * input is exhausted. Here is a version that pads the shorter
   * stream with values. 
   */
   
  def zipWithAll[I,I2,O](padI: I, padI2: I2)(
                         f: (I,I2) => O): Tee[I,I2,O] = {
    val fbR = passR[I2] map (f(padI, _    ))
    val fbL = passL[I]  map (f(_   , padI2))
    receiveLOr(fbR: Tee[I,I2,O])(i => 
    receiveROr(fbL: Tee[I,I2,O])(i2 => emitT(f(i,i2)))) repeat
  }

  def zipAll[I,I2](padI: I, padI2: I2): Tee[I,I2,(I,I2)] = 
    zipWithAll(padI, padI2)((_,_))
  
  def receiveLOr[I,I2,O](fallback: Tee[I,I2,O])(
                       recvL: I => Tee[I,I2,O]): Tee[I,I2,O] =
    receiveL(recvL, fallback)

  def receiveROr[I,I2,O](fallback: Tee[I,I2,O])(
                       recvR: I2 => Tee[I,I2,O]): Tee[I,I2,O] =
    receiveR(recvR, fallback)

  /* Ignores all input from left. */
  def passR[I2]: Tee[Any,I2,I2] = awaitR[I2].flatMap(emitT).repeat
  
  /* Ignores input from the right. */
  def passL[I]: Tee[I,Any,I] = awaitL[I].flatMap(emitT).repeat
  
  /* Alternate pulling values from the left and the right inputs. */
  def interleaveT[I]: Tee[I,I,I] = repeat { for {
    i1 <- awaitL[I]
    i2 <- awaitR[I]
    r <- emitT(i1) ++ emitT(i2)
  } yield r }

 
  implicit class EvalProcess[F[_],O](self: Process[F,F[O]]) {
    def eval: Process[F,O] = self match {
      case Halt => Halt
      case Emit(h, t) => 
        if (h.isEmpty) t.eval
        else await[F,O,O](h.head)(o => emit(o, emitAll(h.tail, t).eval))
      case Await(req,recv,fb,c) => 
        await(req)(recv andThen (_ eval), fb.eval, c.eval) 
    }
  }

  /** 
   * Provides infix syntax for converting a `Wye` to a `Process1`, 
   * by converting requests for the detached side into `Halt`.
   */
  implicit class DetachableWye[I,I2,O](self: Wye[I,I2,O]) {
    def detachL: Process1[I2,O] = self match {
      case Halt => Halt
      case Emit(h, t) => Emit(h, t.detachL)
      case Await(req, recv, fb, c) => (req.tag: @annotation.switch) match {
        case 0 => Halt
        case 1 => Await(Get[I2], recv andThen (_ detachL), fb.detachL, c.detachL)
        case 2 => Await(Get[I2], (These.That(_:I2)) andThen recv andThen (_ detachL), fb.detachL, c.detachL)
      } 
    } 
    def detachR: Process1[I,O] = self match {
      case Halt => Halt
      case Emit(h, t) => Emit(h, t.detachR)
      case Await(req, recv, fb, c) => (req.tag: @annotation.switch) match {
        case 0 => Await(Get[I], recv andThen (_ detachR), fb.detachR, c.detachR)
        case 1 => Halt
        case 2 => Await(Get[I], (These.This(_:I)) andThen recv andThen (_ detachR), fb.detachR, c.detachR)
      }
    }
  }
                          /*                       

  Our `Process` type can also represent effectful sinks (like a file).
  A `Sink` is simply a source of effectful functions! See the
  definition of `to` in `Process` for an example of how to feed a 
  `Process` to a `Sink`.

                           */

  type Sink[F[_],O] = Process[F, O => F[Unit]]

  type Channel[F[_],I,O] = Process[F, I => F[O]]

  // boilerplate to enable monadic infix syntax without explicit imports 

  import scalaz.syntax.{ApplyOps, ApplicativeOps, FunctorOps, MonadOps}
  
  trait ProcessTC[F[_]] { type f[y] = Process[F,y] }

  implicit def toMonadOps[F[_],A](f: Process[F,A]): MonadOps[ProcessTC[F]#f,A] = 
    processInstance.monadSyntax.ToMonadOps(f)
  implicit def toApplicativeOps[F[_],A](f: Process[F,A]): ApplicativeOps[ProcessTC[F]#f,A] = 
    processInstance.applicativeSyntax.ToApplicativeOps(f)
  implicit def toApplyOps[F[_],A](f: Process[F,A]): ApplyOps[ProcessTC[F]#f,A] = 
    processInstance.applySyntax.ToApplyOps(f)
  implicit def toFunctorOps[F[_],A](f: Process[F,A]): FunctorOps[ProcessTC[F]#f,A] =
    processInstance.functorSyntax.ToFunctorOps(f)
}

