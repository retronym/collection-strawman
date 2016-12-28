package strawman.collection.immutable

import strawman.collection.mutable.Iterator
import strawman.collection.{IndexedSeq, Iterable, IterableFactory, IterableOnce, IterablePolyTransformsFromIterable, Seq, SeqLike, SeqMonoTransformsFromIterable}

import scala.{Boolean, IllegalArgumentException, IndexOutOfBoundsException, Int, inline, Long, StringContext, Serializable, SerialVersionUID, Unit}
import scala.Predef.{???, augmentString}
import java.lang.String

/** The `Range` class represents integer values in range
  *  ''[start;end)'' with non-zero step value `step`.
  *  It's a special case of an indexed sequence.
  *  For example:
  *
  *  {{{
  *     val r1 = 0 until 10
  *     val r2 = r1.start until r1.end by r1.step + 1
  *     println(r2.length) // = 5
  *  }}}
  *
  *  Ranges that contain more than `Int.MaxValue` elements can be created, but
  *  these overfull ranges have only limited capabilities. Any method that
  *  could require a collection of over `Int.MaxValue` length to be created, or
  *  could be asked to index beyond `Int.MaxValue` elements will throw an
  *  exception. Overfull ranges can safely be reduced in size by changing
  *  the step size (e.g. `by 3`) or taking/dropping elements. `contains`,
  *  `equals`, and access to the ends of the range (`head`, `last`, `tail`,
  *  `init`) are also permitted on overfull ranges.
  *
  *  @param start       the start of this range.
  *  @param end         the end of the range.  For exclusive ranges, e.g.
  *                     `Range(0,3)` or `(0 until 3)`, this is one
  *                     step past the last one in the range.  For inclusive
  *                     ranges, e.g. `Range.inclusive(0,3)` or `(0 to 3)`,
  *                     it may be in the range if it is not skipped by the step size.
  *                     To find the last element inside a non-empty range,
  *                     use `last` instead.
  *  @param step        the step for the range.
  *  @param isInclusive whether the end of the range is included or not
  */
@SerialVersionUID(7618862778670199309L)
final class Range(
  val start: Int,
  val end: Int,
  val step: Int,
  val isInclusive: Boolean
)
  extends IndexedSeq[Int]
    with IterableFactory[IndexedSeq]
    with IterablePolyTransformsFromIterable[Int, IndexedSeq]
    with SeqMonoTransformsFromIterable[Int, IndexedSeq[Int]]
    with Serializable { range =>

  def iterator(): Iterator[Int] =
    new Iterator[Int] {
      private var current: Seq[Int] = range
      def next() = {
        val x = current.head
        current = current.tail
        x
      }
      def hasNext = !current.isEmpty
    }

  private def gap           = end.toLong - start.toLong
  private def isExact       = gap % step == 0
  private def hasStub       = isInclusive || !isExact
  private def longLength    = gap / step + ( if (hasStub) 1 else 0 )

  override val isEmpty: Boolean = (
    (start > end && step > 0)
      || (start < end && step < 0)
      || (start == end && !isInclusive)
    )

  private val numRangeElements: Int = {
    if (step == 0) throw new IllegalArgumentException("step cannot be 0.")
    else if (isEmpty) 0
    else {
      val len = longLength
      if (len > scala.Int.MaxValue) -1
      else len.toInt
    }
  }

  // This field has a sensible value only for non-empty ranges
  private val lastElement = step match {
    case 1  => if (isInclusive) end else end-1
    case -1 => if (isInclusive) end else end+1
    case _  =>
      val remainder = (gap % step).toInt
      if (remainder != 0) end - remainder
      else if (isInclusive) end
      else end - step
  }

  /** The last element of this range.  This method will return the correct value
    *  even if there are too many elements to iterate over.
    */
  def last: Int = if (isEmpty) Nil.head else lastElement
  override def head: Int = if (isEmpty) Nil.head else start

  /** Creates a new range containing all the elements of this range except the first one.
    *
    *  $doesNotUseBuilders
    *
    *  @return  a new range consisting of all the elements of this range except the first one.
    */
  override def tail: Range = {
    if (isEmpty)
      Nil.tail

    drop(1)
  }

  protected def copy(start: Int = start, end: Int = end, step: Int = step, isInclusive: Boolean = isInclusive): Range =
    new Range(start, end, step, isInclusive)

  /** Create a new range with the `start` and `end` values of this range and
    *  a new `step`.
    *
    *  @return a new range with a different step
    */
  def by(step: Int): Range = copy(start, end, step)

  // Override for performance
  override def size: Int = numRangeElements
  def length: Int = numRangeElements

  // Check cannot be evaluated eagerly because we have a pattern where
  // ranges are constructed like: "x to y by z" The "x to y" piece
  // should not trigger an exception. So the calculation is delayed,
  // which means it will not fail fast for those cases where failing was
  // correct.
  private def validateMaxLength() {
    if (numRangeElements < 0)
      fail()
  }
  private def description = "%d %s %d by %s".format(start, if (isInclusive) "to" else "until", end, step)
  private def fail() = throw new IllegalArgumentException(description + ": seqs cannot contain more than Int.MaxValue elements.")

  def apply(idx: Int): Int = {
    validateMaxLength()
    if (idx < 0 || idx >= numRangeElements) throw new IndexOutOfBoundsException(idx.toString)
    else start + (step * idx)
  }

  def fromIterable[B](it: Iterable[B]): IndexedSeq[B] =
    Array.fromIterable(it) // TODO Use Vector instead of Array

  protected[this] def fromIterableWithSameElemType(coll: Iterable[Int]): IndexedSeq[Int] =
    fromIterable(coll)

  @inline override def foreach(f: Int => Unit) {
    // Implementation chosen on the basis of favorable microbenchmarks
    // Note--initialization catches step == 0 so we don't need to here
    if (!isEmpty) {
      var i = start
      while (true) {
        f(i)
        if (i == lastElement) return
        i += step
      }
    }
  }

  /** Creates a new range containing the first `n` elements of this range.
    *
    *  @param n  the number of elements to take.
    *  @return   a new range consisting of `n` first elements.
    */
  override def take(n: Int): Range =
    if (n <= 0 || isEmpty) newEmptyRange(start)
    else if (n >= numRangeElements && numRangeElements >= 0) this
    else {
      // May have more than Int.MaxValue elements in range (numRangeElements < 0)
      // but the logic is the same either way: take the first n
      new Range(start, locationAfterN(n - 1), step, isInclusive = true)
    }

  /** Creates a new range containing all the elements of this range except the first `n` elements.
    *
    *  @param n  the number of elements to drop.
    *  @return   a new range consisting of all the elements of this range except `n` first elements.
    */
  override def drop(n: Int): Range =
    if (n <= 0 || isEmpty) this
    else if (n >= numRangeElements && numRangeElements >= 0) newEmptyRange(end)
    else {
      // May have more than Int.MaxValue elements (numRangeElements < 0)
      // but the logic is the same either way: go forwards n steps, keep the rest
      copy(locationAfterN(n), end, step)
    }

  // Methods like apply throw exceptions on invalid n, but methods like take/drop
  // are forgiving: therefore the checks are with the methods.
  private def locationAfterN(n: Int) = start + (step * n)

  // When one drops everything.  Can't ever have unchecked operations
  // like "end + 1" or "end - 1" because ranges involving Int.{ MinValue, MaxValue }
  // will overflow.  This creates an exclusive range where start == end
  // based on the given value.
  private def newEmptyRange(value: Int) = new Range(value, value, step, isInclusive = false)

  /** Returns the reverse of this range.
    */
  override def reverse: Range =
    if (isEmpty) this
    else new Range(last, start, -step, isInclusive = true)

  /** Make range inclusive.
    */
  def inclusive: Range =
    if (isInclusive) this
    else new Range(start, end, step, isInclusive = true)

  override def toString: String = {
    val preposition = if (isInclusive) "to" else "until"
    val stepped = if (step == 1) "" else s" by $step"
    val prefix = if (isEmpty) "empty " else if (!isExact) "inexact " else ""
    s"${prefix}Range $start $preposition $end$stepped"
  }

  // TODO Override min, max, slice, init, takeWhile, dropWhile, span, splitAt, takeRight, dropRight, contains, sum, equals
}

object Range {

  /** Counts the number of range elements.
    *  @pre  step != 0
    *  If the size of the range exceeds Int.MaxValue, the
    *  result will be negative.
    */
  def count(start: Int, end: Int, step: Int, isInclusive: Boolean): Int = {
    if (step == 0)
      throw new IllegalArgumentException("step cannot be 0.")

    val isEmpty = (
      if (start == end) !isInclusive
      else if (start < end) step < 0
      else step > 0
    )
    if (isEmpty) 0
    else {
      // Counts with Longs so we can recognize too-large ranges.
      val gap: Long    = end.toLong - start.toLong
      val jumps: Long  = gap / step
      // Whether the size of this range is one larger than the
      // number of full-sized jumps.
      val hasStub      = isInclusive || (gap % step != 0)
      val result: Long = jumps + ( if (hasStub) 1 else 0 )

      if (result > scala.Int.MaxValue) -1
      else result.toInt
    }
  }
  def count(start: Int, end: Int, step: Int): Int =
    count(start, end, step, isInclusive = false)

  /** Make a range from `start` until `end` (exclusive) with given step value.
    * @note step != 0
    */
  def apply(start: Int, end: Int, step: Int): Range = new Range(start, end, step, isInclusive = false)

  /** Make a range from `start` until `end` (exclusive) with step value 1.
    */
  def apply(start: Int, end: Int): Range = new Range(start, end, 1, isInclusive = false)

  /** Make an inclusive range from `start` to `end` with given step value.
    * @note step != 0
    */
  def inclusive(start: Int, end: Int, step: Int): Range = new Range(start, end, step, isInclusive = true)

  /** Make an inclusive range from `start` to `end` with step value 1.
    */
  def inclusive(start: Int, end: Int): Range = new Range(start, end, 1, isInclusive = true)



}