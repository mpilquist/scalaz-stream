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

import scala.annotation.nowarn
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.{Iterable => GIterable}
import scala.collection.generic.{CanBuildFrom, GenericCompanion}
import scala.collection.mutable.{Builder, WrappedArray}
import scala.reflect.ClassTag

private[fs2] trait ChunkPlatform[+O] {
  self: Chunk[O] =>

  def asSeqPlatform: Option[IndexedSeq[O]] =
    None
}

private[fs2] trait ChunkAsSeqPlatform[+O] {
  self: ChunkAsSeq[O] =>

  override val hasDefiniteSize: Boolean =
    true

  override def copyToArray[O2 >: O](xs: Array[O2], start: Int, len: Int): Unit =
    chunk.take(len).copyToArray(xs, start)

  @nowarn("cat=unused")
  override def map[O2, That](f: O ⇒ O2)(implicit bf: CanBuildFrom[IndexedSeq[O], O2, That]): That =
    new ChunkAsSeq(chunk.map(f)).asInstanceOf[That]

  @nowarn("cat=unused")
  override def zipWithIndex[O2 >: O, That](implicit
      bf: CanBuildFrom[IndexedSeq[O], (O2, Int), That]
  ): That =
    new ChunkAsSeq(chunk.zipWithIndex).asInstanceOf[That]

  override def to[Col[_]](implicit
      cbf: CanBuildFrom[Nothing, O, Col[O @uncheckedVariance]]
  ): Col[O @uncheckedVariance] =
    chunk.to(cbf)

  override def genericBuilder[B]: Builder[B, IndexedSeq[B]] =
    Vector.newBuilder

  override def companion: GenericCompanion[IndexedSeq] =
    Vector
}

private[fs2] trait ChunkCompanionPlatform {
  self: Chunk.type =>

  protected def platformFrom[O](i: GIterable[O]): Option[Chunk[O]] =
    i match {
      case wrappedArray: WrappedArray[O] =>
        val arr = wrappedArray.array.asInstanceOf[Array[O]]
        Some(array(arr)(ClassTag(arr.getClass.getComponentType)))

      case _ =>
        None
    }

  /** Creates a chunk backed by a `WrappedArray`
    */
  @deprecated(
    "Use the `from` general factory instead",
    "3.9.0"
  )
  def wrappedArray[O](wrappedArray: WrappedArray[O]): Chunk[O] =
    from(wrappedArray)
}
