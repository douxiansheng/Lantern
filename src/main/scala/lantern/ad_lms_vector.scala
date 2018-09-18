package lantern

import scala.util.continuations._
import scala.util.continuations

import org.scala_lang.virtualized.virtualize
import org.scala_lang.virtualized.SourceContext

import scala.virtualization.lms._

import scala.collection.mutable.ArrayBuffer
import scala.collection.{Seq => NSeq}
import scala.math._

trait TensorExp extends Dsl {

  /**
    Memory Management:
      finally we used a temperate solution called "memory arena". The base code will claim a large piece of code for the whole program.
      internally, every malloc will borrow memory from this arena.
      By using getAllocMem and setAllocMem, we can selectively return a big trunk of memory after one iteration of training.
   **/

  class Timer (val index: Int){
    unchecked[Unit](s"clock_t begin_$index, end_$index; double time_spent_$index")
    def startTimer = { unchecked[Unit](s"begin_$index = clock()") }
    def stopTimer = { unchecked[Unit](s"end_$index = clock()") }
    def printElapsedTime = {
      unchecked[Unit](
        s"end_$index = clock(); printf(",
        "\"Time elapsed: %f\\n\", ",
        s"(double)(end_$index - begin_$index) / CLOCKS_PER_SEC)")
    }
  }

  object Timer {
    var index: Int = 0
    def apply(): Timer = {
      val timer = new Timer(index)
      index += 1
      timer
    }
  }

  def get_time() = unchecked[Double]("((double)clock() / CLOCKS_PER_SEC)")

  class Timer2 (index: Int) {
    unchecked[Unit](s"struct timeval begin_$index, end_$index, diff_$index")
    def startTimer = { unchecked[Unit](s"gettimeofday(&begin_$index, NULL)") }
    def getElapsedTime: Rep[Long] = {
      unchecked[Unit](s"gettimeofday(&end_$index, NULL)")
      unchecked[Unit](s"timeval_subtract(&diff_$index, &end_$index, &begin_$index);")
      unchecked[Long](s"((diff_$index.tv_sec * 1000000L) + (diff_$index.tv_usec))")
    }
  }

  object Timer2 {
    var index: Int = 0
    def apply(): Timer2 = {
      val timer = new Timer2(index)
      index += 1
      timer
    }
  }

  object Dataset {

    class DataLoader(name: String, train: Boolean, mean: Float, std: Float, dims: Int*) {

      def remap[T:Typ] = if (typ[T] == typ[Float]) "float"
        else if (typ[T] == typ[Int]) "int"
        else ???
      def open(path: Rep[String]) = uncheckedPure[Int]("open(",path,",0)")
      def filelen(fd: Rep[Int]) = uncheckedPure[Long]("fsize(",fd,")") // FIXME: fresh name
      def mmap[T:Typ](fd: Rep[Int], len: Rep[Long]) = uncheckedPure[Array[T]]("(",remap(typ[T]),"*)mmap(0, ",len,", PROT_READ | PROT_WRITE, MAP_FILE | MAP_PRIVATE, ",fd,", 0)")

      val fd = open(s"data/bin/${name}_${if (train) "train" else "test"}.bin")
      val len = filelen(fd)
      val data = mmap[Float](fd, len)
      val dLength = (len/4L).toInt

      val tfd = open(s"data/bin/${name}_${if (train) "train" else "test"}_target.bin")
      val tlen = filelen(tfd)
      val target = mmap[Int](tfd, tlen)
      val length = (tlen/4L).toInt

      def dataset = new Tensor(data, NSeq(60000, dims(1), dims(2)))

      @virtualize
      def normalize() = {
        this.foreach { (i, t, d) =>
          t.normalize(mean, std, inPlace = true)
        }
      }

      @virtualize
      def foreach(f: (Rep[Int], Tensor, Rep[Int]) => Unit) = {
        var off = var_new(0)
        for (img <- 0 until length: Rep[Range]) {
          val dataPtr = slice(data, off)
          val t = Tensor(dataPtr, dims : _*)
          f(img, t, target(img))
          off += t.nbElem
        }
        assertC(off == dLength, "Data length doesn't match\\n")
      }
    }
  }

  def convSize(size: Int, kernelSize: Int, strideSize: Int) = (size - kernelSize)/strideSize + 1
  def mmax(a: Int, b: Int) = if (a >= b) a else b

  @virtualize
  def assertC(cond: Rep[Boolean], msg: String, args: Rep[Any]*): Unit = {
    if(!cond) { printf(msg, args : _*); exit() }
  }

  def slice(arr: Rep[Array[Float]], off: Rep[Int]) = uncheckedPure[Array[Float]](arr, "+", off)
  def sliceI(arr: Rep[Array[Int]], off: Rep[Int]) = uncheckedPure[Array[Int]](arr, "+", off)

  object Encoding {
    val ix_a = 96  // index starts from 1

    def char_to_ix(ch: Rep[Char]): Rep[Int] = ch.AsInstanceOf[Int] - ix_a
    def ix_to_char(ix: Rep[Int]): Rep[Char] = (ix + ix_a).AsInstanceOf[Char]
  }

  case class TTT(seq: NSeq[Int]) {
    def apply(x: Int) = {
      if (x >= seq.length) ???
      seq(x)
    }

    def last = seq.last
    def reverse = TTT(seq.reverse)

    def equal(that: TTT) = {
      that.seq == seq
    }
  }

  implicit def ttttoSeq(x: TTT) = x.seq

  object Random {
    def rand() = unchecked[Float]("(float)rand()/RAND_MAX")
    def srand(seed: Option[Int] = None) = unchecked[Unit]("srand(",seed.map(_.toString).getOrElse("time(NULL)"),")")
  }

  def exit() = unchecked[Unit]("exit(0)")

  abstract class DataLoop {
    def foreach(f: Rep[Int] => Unit): Unit
  }

  @virtualize
  object DataLoop {
    def apply(size: Int) = if (size <= 1) {
      new DataLoop {
        def foreach(f: Rep[Int] => Unit) = {
          for (i <- 0 until size: Range) f(unit(i))
        }
      }
    } else {
      new DataLoop {
        def foreach(f: Rep[Int] => Unit) = {
          for (i <- 0 until size: Rep[Range]) f(i)
        }
      }
    }
  }

  /* Not supported in LMS?? 
  abstract class ForLoop {
    def foreach(f: Rep[Int] => Unit): Unit
  }

  @virtualize
  object ForLoop {
    def apply(start: Int, step: Int, step_size: Int) = if (step <= 5) {
      new ForLoop {
        def foreach(f: Rep[Int] => Unit) = {
          for (i <- (start until (start + step_size * step) by step_size): Range) f(unit(i)) 
        }
      }
    } else {
      new ForLoop {
        def foreach(f: Rep[Int] => Unit) = {
          for (i <- (start until (start + step * step_size) by step_size): Rep[Range]) f(i)
        }
      }
    }
  } */

  /**
    * Defines tensor-specific operations.
    * Eventually, a tensor operation IR may be introduced to enable analyses/transformations.
    */
  trait Backend {
    def dot(x: Tensor, y: Tensor): Tensor
    // TODO: Add more ops.
  }

  /**
    * Native tensor op backend.
    * Tensor ops are defined in terms of primitive operations.
    */
  trait BackendNative extends Backend {
    override def dot(x: Tensor, y: Tensor): Tensor = {
      // TODO: remove loop if not needed
      val off = var_new(0)
      val up = if (x.nbDims > 1) x.dims(0) else 1
      val res = NewArray[Float](up)
      for (j <- DataLoop(up)) {
        //for (j <- (0 until up): Rep[Range]) {
        val value = var_new(0.0f)
        for (i <- DataLoop(x.dims.last)) {
          //for (i <- (0 until x.dims.last): Rep[Range]) {
          value += x.data(off) * y.data(i)
          off += 1
        }
        res(j) = readVar(value)
      }
      val dim = if (x.nbDims == 1) 1 else x.dims(0)
      Tensor(res, dim)
    }
  }

  /**
    * cuBLAS tensor op backend. WIP.
    */
  trait BackendCUBLAS extends Backend {
    // GEMM reference:
    // https://docs.nvidia.com/cuda/cublas/index.html#cublas-lt-t-gt-gemm
    //
    // cublasStatus_t cublasSgemm(cublasHandle_t handle,
    //                            cublasOperation_t transa, cublasOperation_t transb,
    //                            int m, int n, int k,
    //                            const float           *alpha,
    //                            const float           *A, int lda,
    //                            const float           *B, int ldb,
    //                            const float           *beta,
    //                            float           *C, int ldc)
    def sgemm(a: Array[Float], b: Array[Float], c: Array[Float]) = unchecked[Array[Float]]("cublasSgemm(...)")

    override def dot(x: Tensor, y: Tensor): Tensor = ???
  }

  /**
    * Default tensor op backend, extending `BackendNative`.
    */
  class BackendDefault extends BackendNative
  val backend: Backend = new BackendDefault

  class Tensor(val data: Rep[Array[Float]], val dimsSeq: NSeq[Int]) extends Serializable {

    val MAX_DOUBLE = 1e10f // FIXME

    val strides = (dimsSeq :\ NSeq[Int]()) {
      case (dimX, seq@(h +: t)) => (dimX * h) +: seq
      case (dimX, _) => NSeq(dimX)
    }

    def dims = TTT(dimsSeq)

    assert(strides.length >= 1)
    assert(strides(0) != 0, "Empty Tensor!!!")

    val nbElem = strides(0)

    val nbDims = dimsSeq.length
    val isScalar = nbElem == 1

    def apply(i: Rep[Int]) = data(i)
    def apply(i: Rep[Int], j: Rep[Int]) = data(i * dims(1) + j) // FIXME the index of matrix is not the normal way

    @virtualize
    def clipAt(bound: Float) = {
      for (i <- DataLoop(nbElem)) {
        if (data(i) > bound) data(i) = bound
        if (data(i) < -1.0f * bound) data(i) = -1.0f * bound
      }
    }

    def mapInPlace(op: Rep[Float] => Rep[Float]) = {
      for (i <- DataLoop(nbElem)) this.data(i) = op(this.data(i))
    }

    def map(op: Rep[Float] => Rep[Float]) = {
      val res = NewArray[Float](nbElem)
      for (i <- DataLoop(nbElem)) res(i) = op(this.data(i))
      new Tensor(res, dims)
    }

    def fold(init: Rep[Float])(op: (Rep[Float], Rep[Float]) => Rep[Float]) = {
      val res = var_new[Float](init)
      for (i <- DataLoop(nbElem)) var_assign(res, op(res, this.data(i)))
      res
    }

    def +(that: Rep[Float]): Tensor = this.map(x => x + that)
    def +(that: Tensor): Tensor = {
      if (nbElem == 1) that + this.data(0)
      else if (that.nbElem == 1) this + that.data(0)
      else if (that.dims == this.dims) {
        val res = NewArray[Float](nbElem)
        for (i <- DataLoop(nbElem)) res(i) = this.data(i) + that.data(i)
        new Tensor(res, dims)
      }
      else throw new IllegalArgumentException(s"dimensions of vector do not match +! ${this.dims.seq} != ${that.dims.seq}")
    }

    // this operator updates the values of this, unlike the + operator
    def +=(that: Rep[Float]): Unit = this.mapInPlace(x => x + that)
    def += (that: Tensor): Unit = {
      if (that.nbElem == 1) {
        generate_comment("+= tensor of dim 0")
        this += that.data(0) // broadcast
      }
      else if (this.nbElem == 1) ??? // this.data(0) = that.fold(this.data(0))((agg, x) => agg + x)
      else if (this.dims == that.dims)
        for (i <- DataLoop(nbElem)) this.data(i) += that.data(i)
      else throw new IllegalArgumentException(s"dimensions of vector do not match +=! ${this.dims.seq} != ${that.dims.seq}")
    }

    def -(that: Rep[Float]): Tensor = this.map(x => x - that)
    def -(that: Tensor): Tensor = {
      if (nbElem == 1) that.map(x => this.data(0) - x)
      else if (that.nbElem == 1) this - that.data(0)
      else if (that.dims == this.dims) {
        val res = NewArray[Float](nbElem)
        for (i <- DataLoop(nbElem)) res(i) = this.data(i) - that.data(i)
        new Tensor(res, dims)
      }
      else throw new IllegalArgumentException("dimensions of vector do not match +!")
    }

    // this operator updates the values of this, unlike the - operator
    def -=(that: Rep[Float]): Unit = this.mapInPlace(x => x - that)
    def -= (that: Tensor): Unit = {
      if (that.nbElem == 1) this -= that.data(0) // broadcast
      else if (this.nbElem == 1) {
        ???
        // this.data(0) = that.fold(this.data(0))((agg, x) => agg - x)
      }
      else if (this.dims == that.dims)
        for (i <- DataLoop(nbElem)) this.data(i) -= that.data(i)
      else throw new IllegalArgumentException("dimensions of vector do not match +=!")
    }

    // Element wise multiplication
    def *(that: Rep[Float]): Tensor = this.map(x => x * that)
    def *(that: Tensor): Tensor = {
      if (nbElem == 1) that * this.data(0)
      else if (that.nbElem == 1) this * that.data(0)
      else if (that.dims == this.dims) {
        val res = NewArray[Float](nbElem)
        for (i <- DataLoop(nbElem)) res(i) = this.data(i) * that.data(i)
        new Tensor(res, dims)
      }
      else throw new IllegalArgumentException(s"dimensions of vector do not match * ${this.dims.seq} != ${that.dims.seq}")
    }

    // this operator updates the values of this, unlike the * operator
    def *=(that: Rep[Float]): Unit = this.mapInPlace(x => x * that)
    def *= (that: Tensor): Unit = {
      if (that.nbElem == 1) this *= that.data(0) // broadcast
      else if (this.nbElem == 1) {
        ???
        // this.data(0) = that.fold(this.data(0))((agg, x) => agg * x)
      }
      else if (this.dims == that.dims)
        for (i <- DataLoop(nbElem)) this.data(i) *= that.data(i)
      else throw new IllegalArgumentException("dimensions of vector do not match +=!")
    }

    // element wise division
    def /(that: Rep[Float]): Tensor = this.map(x => x / that)
    def /(that: Tensor): Tensor = {
      if (nbElem == 1) that.map(x => this.data(0) / x)
      else if (that.nbElem == 1) this / that.data(0)
      else if (that.dims == this.dims) {
        val res = NewArray[Float](nbElem)
        for (i <- DataLoop(nbElem)) res(i) = this.data(i) / that.data(i)
        new Tensor(res, dims)
      }
      else throw new IllegalArgumentException("dimensions of vector do not match +!")
    }

    // this operator updates the values of this, unlike the / operator
    def /=(that: Rep[Float]): Unit = this.mapInPlace(x => x / that)
    def /= (that: Tensor): Unit = {
      if (that.nbElem == 1) this /= that.data(0) // broadcast
      else if (this.nbElem == 1) ??? // this.data(0) = that.fold(this.data(0))((agg, x) => agg / x)
      else if (this.dims == that.dims)
        for (i <- DataLoop(nbElem)) this.data(i) /= that.data(i)
      else throw new IllegalArgumentException("dimensions of vector do not match +=!")
    }

    def setAsOne() = { this.mapInPlace(x => 1.0f); () }
    def clear() = { this.mapInPlace(x => 0.0f); () }

    def copy_data(that: Tensor) = {
      assert(this.nbElem == that.nbElem, "dimensions of vector do not match copy_data!")
      for (i <- DataLoop(nbElem)) this.data(i) = that.data(i)
    }

    // NOTE: only handles (Matrix dot Vector) and (Vector dot Vector)
    def dot(that: Tensor) = {
      // assert that and this have the same dimension
      generate_comment(s"dot ${this.dims.seq} - ${that.dims.seq}")
      assert(this.nbDims <= 2 && that.nbDims == 1, s"Only M x V or V x V allowed ${this.dims} - ${that.dims}")
      assert(this.dims.last == that.dims(0), s"dimensions of vector do not match dot! ${this.dims.seq} - ${that.dims.seq}")
      backend.dot(this, that)
    }

    // NOTE: only handles (Vector cart Vector)
    def cart(that: Tensor) = {
      assert(this.nbDims == 1 && that.nbDims == 1, "cartesian product is only for 1d vectors")
      val res = NewArray[Float](this.dims(0) * that.dims(0))
      val off = var_new(0)
      for (i <- DataLoop(this.dims(0))) {
      //for (i <- (0 until this.dims(0)): Rep[Range]) {
        for (j <- DataLoop(that.dims(0))) {
        //for (j <- (0 until that.dims(0)): Rep[Range]) {
          res(off) = data(i) * that.data(j)
          off += 1
        }
      }
      Tensor(res, this.dims(0), that.dims(0))
    }

    def trans() = {
      assert(this.nbDims == 2, "transpose is only for matrix. Tensor transpose is not supported here")
      val res = NewArray[Float](this.nbElem)
      val offT = var_new(0)
      for (i <- DataLoop(this.dims(1))) {
      //for (i <- (0 until this.dims(1)): Rep[Range]) {
        val off = var_new(0)
        for (j <- DataLoop(this.dims(0))) {
        //for (j <- (0 until this.dims(0)): Rep[Range]) {
          res(offT + j) = data(off + i)
          off += this.dims(1)
        }
        offT += this.dims(0)
      }
      new Tensor(res, this.dims.reverse)
    }

    def tanh() = this.map(x => Math.tanh(x).toFloat)
    def exp() = this.map(x => Math.exp(x).toFloat)
    def log() = this.map(x => Math.log(x).toFloat)
    def sqrt() = this.map(x => Math.sqrt(x).toFloat)
    def sigmoid() = this.map(x => 1.0f / (Math.exp(-1.0f * x).toFloat + 1.0f))

    // NOTE: sum all elements
    def sum() = Tensor.scalar(this.fold(0.0f)(_ + _))

    @virtualize
    def sum2D(dim: Int) = {

      assert (this.nbDims == 2, "Only deal with 2D tensor")
      assert (dim == 0 || dim == 1, "dim must be in range of this.nbDims")

      if (dim == 0) ???
      else {
        val res = NewArray[Float](this.dims(0))
        val offset = var_new(0)
        for (i <- DataLoop(this.dims(0))) {
          val sum = var_new(0.0f)
          for (j <- DataLoop(this.dims(1))) {
            sum += this.data(offset)
            offset += 1
          }
          res(i) = sum
        }
        Tensor(res, this.dims(0))
      }
    }

    @virtualize
    def check(limit: Float) = {
      val idx = var_new(0)
      while (idx < this.nbElem && -limit < this.data(idx) && this.data(idx) < limit) {
        idx += 1
      }

      idx != this.nbElem
    }

    @virtualize
    def max() = this.fold(scala.Float.MinValue)((agg, x) => if (x > agg) x else agg)

    @virtualize
    def max2D(dim: Int) = {
      
      assert (this.nbDims == 2, "Only deal with 2D tensor")
      assert (dim == 0 || dim == 1, "dim must be in range of this.nbDims")
      
      if (dim == 0) ???
      else {
        val res = NewArray[Float](this.dims(0))
        val offset = var_new(0)
        for (i <- DataLoop(this.dims(0))) {
          val max = var_new(scala.Float.MinValue)
          for (j <- DataLoop(this.dims(1))) {
            if (this.data(offset) > max) max = this.data(offset)
            offset += 1  
          }
          res(i) = max           
        }
        Tensor(res, this.dims(0))
      }
    }

    // FIXME: Proper tensor
    @virtualize
    def maxIndex() = {
      assert(this.nbDims == 1)
      val vMax = var_new(this.data(0))
      val iMax = var_new(0)
      for (idx <- 1 until this.nbElem: Rep[Range]) {
        if (this.data(idx) > vMax) {
          iMax = idx
          vMax = this.data(idx)
        }
      }

      iMax
    }

    @virtualize  // batched log softmax
    def logSoftmaxB() = {
      assert(this.nbDims == 2, "logSoftmaxB should handle 2D tensors: batch * 1D")

      val max = this.max2D(dim = 1)
      val res = Tensor.zeros_like(this)
      // fill res with exp(x_i - max)
      val offset = var_new(0)
      for (batch <- DataLoop(this.dims(0))) {
        for (i <- DataLoop(this.dims(1))) {
          res.data(offset) = Math.exp(this.data(offset) - max.data(batch)).toFloat
          offset += 1
        }
      }
      val sum = res.sum2D(dim = 1)
      offset = 0
      for (batch <- DataLoop(res.dims(0))) {
        val logsum = max.data(batch) + Math.log(sum.data(batch)).toFloat
        for (i <- DataLoop(res.dims(1))) {
          res.data(offset) = this.data(offset) - logsum
          offset += 1
        }
      }
      res
    }

    @virtualize
    def logSoftmax() = {
      assert(this.nbDims == 1, "TODO: logSoftmax only handles 1d vectors so far")

      val m = this.max
      val logsum = m + Math.log(this.fold(0.0f)((agg, x) => agg + Math.exp(x - m).toFloat)).toFloat
      this.map(x => x - logsum)
    }

    @virtualize
    def softmax_batch() = {
      assert(this.nbDims == 2, "softmax input should be 2-D (batch * 1D logits)")
      val max = this.max2D(dim = 1)
      val res = Tensor.zeros_like(this)
      val offset = var_new(0)
      for (batch <- DataLoop(this.dims(0))) {
        for (i <- DataLoop(this.dims(1))) {
          res.data(offset) = Math.exp(this.data(offset) - max.data(batch)).toFloat
          offset += 1
        }
      }
      val sum = res.sum2D(dim = 1)
      offset = 0
      for (batch <- DataLoop(res.dims(0))) {
        for (i <- DataLoop(res.dims(1))) {
          res.data(offset) = res.data(offset) / sum.data(batch)
          offset += 1
        }
      }
      res
    }

    @virtualize
    def softmax() = {
      assert(this.nbDims == 1, "TODO: softmax only handles 1d vectors so far: " + this.nbDims)

      val m = this.max
      val normalized = this.map(x => x - m)
      val nor_exp = normalized.exp()
      nor_exp / nor_exp.sum()
    }

    @virtualize
    def nllLoss(target: Rep[Int]) = {
      assert(this.nbDims == 1)

      // assertC(0 <= target && target < this.nbElem, "Incorrect target")
      Tensor.scalar(-1.0f * this.data(target))
    }

    def resize(dims: Int*) = {
      assert(dims.product == this.nbElem)

      Tensor(this.data, dims : _*)
    }


    // NOTE: sum matrix to vector, condense on the dims(1) dimension
    def sumOnDim1() = {
      assert(this.nbDims <= 2)
      if (this.nbDims == 1) this
      else {
        val res = NewArray[Float](this.dims(1))
        val off = var_new(0)
        for (j <- DataLoop(this.dims(1))) {
        //for (j <- (0 until this.dims(1)): Rep[Range]) {
          res(off) = this.data(off)
          off += 1
        }

        for (i <- (1 until this.dims(0)): Rep[Range]) {
          val offR = var_new(0)
          for (j <- DataLoop(this.dims(1))) {
          //for (j <- (0 until this.dims(1)): Rep[Range]) {
            res(offR) += data(off)
            off += 1
            offR += 1
          }
        }
        Tensor(res, this.dims(1))
      }
    }

    def print(msg: String = ""): Unit = {
      if (msg != "")
        printf(s"$msg (size ${this.dims.seq mkString " x "})\\n")
      if (this.nbDims == 4) this.print4D
      else if (this.nbDims == 3) this.print3D
      else this.printRaw(this.dims.last)
    }

    val format = "%.10f "
    def print4D = {
      val idx = var_new(1)
      for (i <- 0 until this.dims(0): Rep[Range]) {
        val idx1 = var_new(1)
        for (j <- 0 until this.dims(1): Rep[Range]) {
          printf(s"Pane #(%d, %d) - ${this.dims(2)} x ${this.dims(3)}\\n", idx, idx1)
          for (k <- 0 until this.dims(2): Rep[Range]) {
            for (l <- 0 until this.dims(3): Rep[Range]) {
              printf(format, this.data(i * this.strides(1) + j * this.strides(2) + k * this.strides(3) + l))
            }
            printf("\\n")
          }
          printf("\\n\\n")
          idx1 += 1
        }
        idx += 1
      }
    }

    def print3D = {
      val idx = var_new(1)
      for (i <- 0 until this.dims(0): Rep[Range]) {
        printf(s"Pane #%d - ${this.dims(1)} x ${this.dims(2)}\\n", idx)
        for (k <- 0 until this.dims(1): Rep[Range]) {
          for (l <- 0 until this.dims(2): Rep[Range]) {
            printf(format, this.data(i * this.strides(1) + k * this.strides(2) + l))
          }
          printf("\\n")
        }
        printf("\\n\\n")
        idx += 1
      }
    }

    @virtualize
    def printRaw(row: Int = 10) = {
      for (i <- 0 until this.nbElem: Rep[Range]) {
        printf(format, data(i))
        val imod = i % row
        if (imod == row - 1)
          printf("\\n")
      }
      printf("\\n")
    }

    // setting: this is matrix, that is dims(0)-sized vector, y is dims(1)-sized vector
    // the result is to update this so that this += that * y, where * is cartesian product
    def add_cartesian(that: Tensor, y: Tensor) = {
      generate_comment("add_cartesian")
      assert(this.nbDims == 2 && that.dims == TTT(NSeq(this.dims(1))) && y.dims == TTT(NSeq(this.dims(0))) ||
        this.nbDims == 1 && that.dims == this.dims && y.isScalar, s"${dims} - ${that.dims} - ${y.dims}")
      val off = var_new(0)
      // TODO remove loop if not used
      val up = if (this.nbDims > 1) this.dims(0) else 1
      for (i <- DataLoop(up)) {
      //for (i <- (0 until up): Rep[Range]) {
        for (j <- DataLoop(dims(1))) {
        //for (j <- (0 until dims(1)): Rep[Range]) {
          this.data(off + j) = this.data(off + j) + that.data(j) * y.data(i)
        }
        off += this.dims(1)
      }
    }
    // FIXME: Maybe try to support slicing??
    // FIXME: Maybe add support for reshaping??
    // FIXME: Maybe support transposing??


    // setting: this is dims(0)-sized vector, that is matrix (dims(0) * dims(1)), y is dims(1)-sized vector
    // the result is to update this so that this accumulate every matrix col * y
    def add_composion(that: Tensor, y: Tensor) = {
      assert(that.nbDims == 2 && this.dims.seq == NSeq(that.dims(1)) && y.dims.seq == NSeq(that.dims(0))
        || that.nbDims == 1 && this.dims == that.dims && y.isScalar, s"${dims} - ${that.dims} - ${y.dims}")
      val off = var_new(0)
      // FIXME!!
      val up = if (that.nbDims > 1) that.dims(0) else 1
      for (i <- DataLoop(up)) {
      //for (i <- (0 until up): Rep[Range]) {
        for (j <- DataLoop(that.dims(1))) {
        //for (j <- (0 until that.dims(1)): Rep[Range]) {
          data(j) += that.data(off + j) * y.data(i)
        }
        off += that.dims(1)
      }
    }
    // def add_composion(that: Tensor, y: Tensor) = {
    //   if (this.nbDims == 1)
    //     this.resize(that.dims(0), )
    // }

    @virtualize
    def addMul(that: Tensor, y: Tensor) = {
      assert(this.nbDims == 2 && that.nbDims == 2 && y.nbDims == 2, s"Dimensions: ${this.dims.seq} - ${that.dims.seq} - ${y.dims.seq}")
      assert(this.dims(0) == that.dims(0) && this.dims(1) == y.dims(1) && that.dims(1) == y.dims(0), s"Dimensions: ${this.dims.seq} + ${that.dims.seq} * ${y.dims.seq}")

      var offThis = var_new(0)
      var offThatR = var_new(0)
      var offYC = var_new(0)
      for (i <- DataLoop(this.dims(0))) {
      //for (i <- 0 until this.dims(0): Rep[Range]) {
        val offYR = var_new(offYC)
        for (j <- DataLoop(this.dims(1))) {
        //for (j <- 0 until this.dims(1): Rep[Range]) {
          val offY = var_new(offYR)
          val offThat = var_new(offThatR)
          for (k <- DataLoop(that.dims(1))) {
          //for (k <- 0 until that.dims(1): Rep[Range]) {
            // assertC(unit(0) <= offThis && offThis < this.nbElem, s"Index error this %d > ${this.nbElem}", offThis)
            // assertC(unit(0) <= offThat && offThat < that.nbElem, s"Index error that %d > ${that.nbElem}", offThat)
            // assertC(unit(0) <= offY && offY < y.nbElem, s"Index error this %d > ${y.nbElem}", offY)
            this.data(offThis) = this.data(offThis) + that.data(offThat) * y.data(offY)
            offThat += 1
            offY += y.strides(1)
          }
          offThis += 1
          offYR += 1
        }
        offThatR += that.strides(1)
        offYC *= 0
      }
    }

    // private function to get data with default to the only element
    def getAt(i: Rep[Int]) = {
      if (this.isScalar) data(0)
      else data(i)
    }
    def square(t: Rep[Float]) = t * t
    def add_mult(a: Tensor, b: Tensor) = {
      assert(Tensor.dimCompatible(a, b) && Tensor.dimCompatible(a, this) && Tensor.dimCompatible(this, b), "dim not Compatible in add_mult")

      // FIXME!!!
      val dims0M = mmax(dims(0), mmax(a.dims(0), b.dims(0)))
      val dims1M = mmax(if (this.nbDims > 1) dims(1) else 1, mmax(if (a.nbDims > 1) a.dims(1) else 1, if (b.nbDims > 1) b.dims(1) else 1))
      //if (this.isScalar) {
      //  for (i <- 0 until (dims0M * dims1M): Rep[Range]) data(0) = data(0) + a.getAt(i) * b.getAt(i)
      //} else {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(i) = data(i) + a.getAt(i) * b.getAt(i)
      //}
      for (i <- DataLoop(dims0M * dims1M)) {
        if (this.isScalar) { data(0) = data(0) + a.getAt(i) * b.getAt(i) }
        else { data(i) = data(i) + a.getAt(i) * b.getAt(i) }
      }
    }

    def addMul(a: Rep[Float], b: Tensor) = {
      assert(this.dims == b.dims)

      generate_comment("Generate code for addMul")
      for (i <- DataLoop(this.nbElem)) {
      //for (i <- 0 until this.nbElem: Rep[Range]) {
        this.data(i) = this.data(i) + a * b.data(i)
      }
    }

    def cmulAdd(a: Float, b: Tensor) = {
      assert(this.dims == b.dims)
      for (i <- DataLoop(this.nbElem))
      //for (i <- 0 until this.nbElem: Rep[Range])
        this.data(i) = a * this.data(i) + b.data(i)

      this // FIXME ??
    }

    def add_div(a: Tensor, b: Tensor) = {
      assert(Tensor.dimCompatible(a, b) && Tensor.dimCompatible(a, this) && Tensor.dimCompatible(this, b), "dim not Compatible in add_div")
      val dims0M = mmax(dims(0), mmax(a.dims(0), b.dims(0)))
      // FIXME
      val dims1M = mmax(if (nbDims > 1) dims(1) else 1, mmax(if (a.nbDims > 1) a.dims(1) else 1, if (b.nbDims > 1) b.dims(1) else 1))
      //if (this.isScalar) {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(0) = data(0) + a.getAt(i) / b.getAt(i)
      //} else {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(i) = data(i) + a.getAt(i) / b.getAt(i)
      //}
      for (i <- DataLoop(dims0M * dims1M)) {
        if (this.isScalar) { data(0) = data(0) + a.getAt(i) / b.getAt(i) }
        else { data(i) = data(i) + a.getAt(i) / b.getAt(i) }
      }
    }

    def minus_mult_div_square(a: Tensor, b: Tensor, c: Tensor) = {
      assert(Tensor.dimCompatible(a, b)    && Tensor.dimCompatible(a, c)    && Tensor.dimCompatible(c, b)    &&
        Tensor.dimCompatible(this, b) && Tensor.dimCompatible(a, this) && Tensor.dimCompatible(this, c),
        "dim not competible in minus_mult_div_square")
      val dims0M = mmax(dims(0), mmax(a.dims(0), mmax(b.dims(0), c.dims(0))))
      // FIXME
      val dims1M = mmax(if (nbDims > 1) dims(1) else 1, mmax(if (a.nbDims > 1) a.dims(1) else 1, if (b.nbDims > 1) b.dims(1) else 1))
      //if (this.isScalar) {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(0) = data(0) - a.getAt(i) * b.getAt(i) / square(c.getAt(i))
      //} else {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(i) = data(i) - a.getAt(i) * b.getAt(i) / square(c.getAt(i))
      //}
      for (i <- DataLoop(dims0M * dims1M)) {
        if (this.isScalar) { data(0) = data(0) - a.getAt(i) * b.getAt(i) / square(c.getAt(i)) }
        else { data(i) = data(i) - a.getAt(i) * b.getAt(i) / square(c.getAt(i)) }
      }
    }

    def add_oneMinusSquare_mult(a: Tensor, b: Tensor) = {
      assert(Tensor.dimCompatible(a, b) && Tensor.dimCompatible(a, this) && Tensor.dimCompatible(this, b), "dim not Compatible in add_oneMinusSquare_mult")
      val dims0M = mmax(dims(0), mmax(a.dims(0), b.dims(0)))
      // FIXME
      val dims1M = mmax(if (nbDims > 1) dims(1) else 1, mmax(if (a.nbDims > 1) a.dims(1) else 1, if (b.nbDims > 1) b.dims(1) else 1))
      //if (this.isScalar) {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(0) = data(0) + (1.0f - square(a.getAt(i))) * b.getAt(i)
      //} else {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(i) = data(i) + (1.0f - square(a.getAt(i))) * b.getAt(i)
      //}
      for (i <- DataLoop(dims0M * dims1M)) {
        if (this.isScalar) { data(0) = data(0) + (1.0f - square(a.getAt(i))) * b.getAt(i) }
        else { data(i) = data(i) + (1.0f - square(a.getAt(i))) * b.getAt(i) }
      }
    }

    def oneMinusThenMult(t: Rep[Float]) = (1.0f - t) * t

    def add_oneMinusThenMult_mult(a: Tensor, b: Tensor) = {
      assert(Tensor.dimCompatible(a, b) && Tensor.dimCompatible(a, this) && Tensor.dimCompatible(this, b), "dim not Compatible in add_oneMinusThenMult_mult")
      val dims0M = mmax(dims(0), mmax(a.dims(0), b.dims(0)))
      // FIXME
      val dims1M = mmax(if (nbDims > 1) dims(1) else 1, mmax(if (a.nbDims > 1) a.dims(1) else 1, if (b.nbDims > 1) b.dims(1) else 1))
      //if (this.isScalar) {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(0) = data(0) + oneMinusThenMult(a.getAt(i)) * b.getAt(i)
      //} else {
      //  for (i <- (0 until dims0M * dims1M): Rep[Range]) data(i) = data(i) + oneMinusThenMult(a.getAt(i)) * b.getAt(i)
      //}
      for (i <- DataLoop(dims0M * dims1M)) {
        if (this.isScalar) { data(0) = data(0) + oneMinusThenMult(a.getAt(i)) * b.getAt(i) }
        else { data(i) = data(i) + oneMinusThenMult(a.getAt(i)) * b.getAt(i) }
      }
    }

    @virtualize
    def conv2D_batch(kernel: Tensor, bias: Tensor, strides: NSeq[Int], pads: NSeq[Int]): Tensor = {
      assert (this.nbDims == 4, "For conv_batch , input should be 4-D, with the first dim to be batch")
      assert(kernel.nbDims == 4, "For Conv, kernel should be 4-D")
      assert(bias.nbDims == 1, "For Conv, bias should be 1-D")
      assert(bias.dims(0) == kernel.dims(0), "For Conv, bias length should be the same as number of kernels")
      assert(kernel.dims(1) == this.dims(1), "For Conv, input dim_0 should be the same as kernel dim_1")
      assert(this.dims(2) >= kernel.dims(2) && this.dims(3) >= kernel.dims(3), "Image too small for Conv")
      
      val totalPads = pads.fold(0)((x: Int, y: Int) => x + y)
      // TODO: (Fei Wang) not sure if the order is correct!!!
      assert(pads.size == 4, "pads should have 4 values, up, down, left, right")
      assert(strides.size == 2, "strides should have a strideRow and a strideCol")
      val ((strideRow:Int) :: (strideCol:Int) :: Nil) = strides.take(2).toList
      val ((padUp:Int) :: (padDown:Int) :: (padLeft:Int) :: (padRight:Int) :: Nil) = pads.take(4).toList
      assert(strideRow >= 1, "stride of row should be at least 1")
      assert(strideCol >= 1, "stride of col should be at least 1")
      assert(padUp == padDown && padUp == padLeft && padUp == padRight, "For now, assume all values in pads are the same")

      val resWidth = convSize(this.dims(2) + padLeft + padRight, kernel.dims(2), strideRow)
      val resHeight = convSize(this.dims(3) + padUp + padDown, kernel.dims(3), strideCol)
      val res = Tensor.zeros(this.dims(0), kernel.dims(0), resWidth, resHeight)  // batch, channel, width, height

      for (i <- DataLoop(this.dims(0))) {
        val ptrInput = slice(this.data, i * this.strides(1))
        val ptrOutput = slice(res.data, i * res.strides(1))
        Tensor(ptrInput, this.dims.drop(1): _*).conv2D_inplace(kernel, bias, strides, pads, Tensor(ptrOutput, res.dims.drop(1): _*))
      }
      res
    }

    @virtualize
    def conv2D_inplace(kernel: Tensor, bias: Tensor, strides: NSeq[Int], pads: NSeq[Int], res: Tensor): Unit = {
      val totalPads = pads.fold(0)((x: Int, y: Int) => x + y)
      val ((strideRow:Int) :: (strideCol:Int) :: Nil) = strides.take(2).toList
      val ((padUp:Int) :: (padDown:Int) :: (padLeft:Int) :: (padRight:Int) :: Nil) = pads.take(4).toList
      val resWidth = res.dims(1)
      val resHeight = res.dims(2)

      val offOut = var_new(0)                         // offset for the res by channel
      val offWeight1 = var_new(0)                     // offset for the kernel by channel (dim_0)
      for (outPane <- DataLoop(kernel.dims(0))) {
        val offWeight2 = var_new(offWeight1)          // offset for the kernel for each z-dim of a given channel
        val offInput = var_new(0)                     // offset for this for each channel of input
        val ptrOutput = slice(res.data, offOut)           // res, restarting from the start of this output channel (2D)
        for (inPane <- DataLoop(this.dims(0))) {
          val ptrInput = slice(this.data, offInput)      // input, restarting from the start of this input channel (2D)
          val ptrWeight = slice(kernel.data, offWeight2)  // kernel, restarting from the start of this input channel (2D)

          if (totalPads == 0) Tensor(ptrOutput, resHeight, resWidth).conv2D1(
            Tensor(ptrInput, this.dims(1), this.dims(2)),
            Tensor(ptrWeight, kernel.dims(2), kernel.dims(3)),
            strideRow, strideCol, bias(outPane))
          else Tensor(ptrOutput, resHeight, resWidth).conv2D2(
            Tensor(ptrInput, this.dims(1), this.dims(2)),
            Tensor(ptrWeight, kernel.dims(2), kernel.dims(3)),
            strideRow, strideCol, padUp, padDown, padLeft, padRight, bias(outPane))

          offWeight2 += kernel.strides(2)
          offInput += this.strides(1)
        }
        offWeight1 += kernel.strides(1)
        offOut += res.strides(1)
      }
    }

    @virtualize
    def conv2D(kernel: Tensor, bias: Tensor, strides: NSeq[Int], pads: NSeq[Int]) = {

      assert(this.nbDims == 3 && kernel.nbDims == 4, "For Conv, input should be 3-D and kernel should be 4-D: " + this.nbDims + "|" + kernel.nbDims)
      assert(kernel.dims(1) == this.dims(0), "For Conv, input dim_0 should be the same as kernel dim_1")
      assert(this.dims(1) >= kernel.dims(2) && this.dims(2) >= kernel.dims(3), "Image too small for Conv")
      
      val totalPads = pads.fold(0)((x: Int, y: Int) => x + y)
      // TODO: (Fei Wang) not sure if the order is correct!!!
      assert(pads.size == 4, "pads should have 4 values, up, down, left, right")
      assert(strides.size == 2, "strides should have a strideRow and a strideCol")
      val ((strideRow:Int) :: (strideCol:Int) :: Nil) = strides.take(2).toList
      val ((padUp:Int) :: (padDown:Int) :: (padLeft:Int) :: (padRight:Int) :: Nil) = pads.take(4).toList
      assert(strideRow >= 1, "stride of row should be at least 1")
      assert(strideCol >= 1, "stride of col should be at least 1")
      assert(padUp == padDown && padUp == padLeft && padUp == padRight, "For now, assume all values in pads are the same")
      
      val resWidth = convSize(this.dims(1) + padLeft + padRight, kernel.dims(2), strideRow)
      val resHeight = convSize(this.dims(2) + padUp + padDown, kernel.dims(3), strideCol)
      val res = Tensor.zeros(kernel.dims(0), resWidth, resHeight)

      val offOut = var_new(0)                         // offset for the res by channel
      val offWeight1 = var_new(0)                     // offset for the kernel by channel (dim_0)
      for (outPane <- DataLoop(kernel.dims(0))) {
        val offWeight2 = var_new(offWeight1)          // offset for the kernel for each z-dim of a given channel
        val offInput = var_new(0)                     // offset for this for each channel of input
        val ptrOutput = slice(res.data, offOut)           // res, restarting from the start of this output channel (2D)
        for (inPane <- DataLoop(this.dims(0))) {
          val ptrInput = slice(this.data, offInput)      // input, restarting from the start of this input channel (2D)
          val ptrWeight = slice(kernel.data, offWeight2)  // kernel, restarting from the start of this input channel (2D)

          if (totalPads == 0) Tensor(ptrOutput, resHeight, resWidth).conv2D1(
            Tensor(ptrInput, this.dims(1), this.dims(2)),
            Tensor(ptrWeight, kernel.dims(2), kernel.dims(3)),
            strideRow, strideCol, bias(outPane))
          else Tensor(ptrOutput, resHeight, resWidth).conv2D2(
            Tensor(ptrInput, this.dims(1), this.dims(2)),
            Tensor(ptrWeight, kernel.dims(2), kernel.dims(3)),
            strideRow, strideCol, padUp, padDown, padLeft, padRight, bias(outPane))

          offWeight2 += kernel.strides(2)
          offInput += this.strides(1)
        }
        offWeight1 += kernel.strides(1)
        offOut += res.strides(1)
      }
      res
    }

    @virtualize
    def conv2D2(input: Tensor, kernel: Tensor, strideRow: Int, strideCol: Int, padUp: Int, padDown: Int, padLeft: Int, padRight: Int, bias: Rep[Float] = 0.0f): Unit = {
      assert(this.nbDims == 2 && input.nbDims == 2 && kernel.nbDims == 2)

      // looping for the output
      val offOutput = var_new(0)                 // offset of the output, move one by one in second loop
      // val offInputR = var_new(0)                 // offset of the input, move by each row * strideRow
      val InputR = var_new(-padLeft)
      for (outRow <- DataLoop(this.dims(0))) {
        // val offInputC = var_new(offInputR)       // offset of the input, build on offInputR, move by each strideCol
        val InputC = var_new(-padUp)
        for (outCol <- DataLoop(this.dims(1))) {

          // looping for the kernel
          val sum = var_new(bias)
          val offKernel = var_new(0)                // offset of the kernel, move by row of kernel
          // val offInput  = var_new(offInputC)     // offset of the input, built on offInputC, move by row of input
          val i = var_new(InputR)
          for (kernelRow <- DataLoop(kernel.dims(0))) {
            // val ptrInput = slice(input.data, offInput)
            val j = var_new(InputC)
            val ptrKernel = slice(kernel.data, offKernel)
            for (kernelCol <- DataLoop(kernel.dims(1))) {
              if (i < 0 || j < 0 || i >= this.dims(0) || j >= this.dims(1)) ()
              else {
                sum += ptrKernel(kernelCol) * input.data(i * input.strides(1) + j)
              }
              j += 1
            }
            offKernel += kernel.strides(1)
            // offInput  += input.strides(1)
            i += 1
          }
          this.data(offOutput) = this.data(offOutput) + sum
          offOutput += 1
          // offInputC += strideCol
          InputC += strideCol
        }
        // offInputR += strideRow * input.strides(1)
        InputR += strideRow
      }
    }

    @virtualize
    def conv2D(kernel: Tensor, strideRow: Int, strideCol: Int): Tensor = {

      assert(this.nbDims == 3 && kernel.nbDims == 4, "input should be 3-D and kernel should be 4-D for Conv")
      assert(strideRow >= 1, "stride of row should be at least 1")
      assert(strideCol >= 1, "stride of col should be at least 1")
      assert(kernel.dims(1) == this.dims(0), "input dim_0 should be the same as kernel dim_1")
      assert(this.dims(1) >= kernel.dims(2) && this.dims(2) >= kernel.dims(3), "Image too small for Conv")

      val resHeight = convSize(this.dims(1), kernel.dims(2), strideRow)
      val resWidth = convSize(this.dims(2), kernel.dims(3), strideCol)
      val res = Tensor.zeros(kernel.dims(0), resHeight, resWidth)

      val offOut = var_new(0)                      // offset for the res for each channel of the output
      val offWeight1 = var_new(0)                  // offset for the kernel for each channel of the output
      for (outPane <- DataLoop(kernel.dims(0))) {
        val offWeight2 = var_new(offWeight1)       // offset for the kernel for each z-dim of a given channel
        val offInput = var_new(0)                  // offset for this for each channel of input
        val ptrOutput = slice(res.data, offOut)          // res, restarting from the start of this output channel (2D)
        for (inPane <- DataLoop(this.dims(0))) {
          val ptrInput = slice(this.data, offInput)     // input, restarting from the start of this input channel (2D)
          val ptrWeight = slice(kernel.data, offWeight2) // kernel, restarting from the start of this input channel (2D)

          Tensor(ptrOutput, resHeight, resWidth).conv2D1(Tensor(ptrInput, this.dims(1), this.dims(2)), Tensor(ptrWeight, kernel.dims(2), kernel.dims(3)), strideRow, strideCol)

          offWeight2 += kernel.strides(2)
          offInput += this.strides(1)
        }
        offWeight1 += kernel.strides(1)
        offOut += res.strides(1)
      }
      res
    }

    // Taken from Torch: THTensorConv.cpp#198L 
    // https://github.com/pytorch/pytorch/blob/master/aten/src/TH/generic/THTensorConv.cpp
    @virtualize
    def conv2D1(input: Tensor, kernel: Tensor, strideRow: Int, strideCol: Int, bias: Rep[Float] = 0.0f): Unit = {
      assert(this.nbDims == 2 && input.nbDims == 2 && kernel.nbDims == 2)

      // looping for the output
      val offOutput = var_new(0)                 // offset of the output, move one by one in second loop
      val offInputR = var_new(0)                 // offset of the input, move by each row * strideRow
      for (outRow <- DataLoop(this.dims(0))) {
        val offInputC = var_new(offInputR)       // offset of the input, built on offInputR, move by each strideCol
        for (outCol <- DataLoop(this.dims(1))) {

          // looping for the kernel
          val sum = var_new(bias)
          val offKernel = var_new(0)             // offset of the kernel, move by kernel.strides(1) i.e. by row of kernel
          val offInput = var_new(offInputC)      // offset of the input, built on offInputC, move by row of input
          for (kernelRow <- DataLoop(kernel.dims(0))) {
            val ptrInput = slice(input.data, offInput)
            val ptrKernel = slice(kernel.data, offKernel)
            for (kernelCol <- DataLoop(kernel.dims(1))) {
              sum +=  ptrInput(kernelCol) * ptrKernel(kernelCol)
            }
            offKernel += kernel.strides(1)
            offInput += input.strides(1)
          }
          this.data(offOutput) = this.data(offOutput) + sum
          offOutput += 1
          offInputC += strideCol
        }
        offInputR += strideRow * input.strides(1)
      }
    }

    @virtualize
    def maxPool(strideRow: Int, strideCol: Int) = {
      assert(this.nbDims == 3)

      val resHeight = this.dims(1) / strideRow
      val resWidth = this.dims(2) / strideCol
      val res = Tensor.fill(scala.Float.MinValue, this.dims(0), resHeight, resWidth)

      // FIXME adhoc transform tensor to be using generic type!
      val savedIdx = NewArray[Int](res.nbElem)

      val oidxW = var_new(0)  // walks by channel in res
      val iidx = var_new(0)   // walks by 1 in input (this.data)
      for (ichan <- DataLoop(this.dims(0))) {
        val oidx = var_new(oidxW)  // walks by row in res
        for (ox <- DataLoop(res.dims(1))) {
          for (sx <- DataLoop(strideRow)) {
            val oidx2 = var_new(oidx)  // walks by 1 in res
            for (oy <- DataLoop(res.dims(2))) {
              for (sy <- DataLoop(strideCol)) {
                if (this.data(iidx) > res.data(oidx2)) {
                  res.data(oidx2) = this.data(iidx)
                  savedIdx(oidx2) = iidx
                }
                iidx += 1
              }
              oidx2 += 1
            }
          }
          oidx += res.strides(2)
        }
        oidxW += res.strides(1)
      }

      (res, savedIdx)
    }

    @virtualize
    def maxPool_k_batch(kernels: Seq[Int], strides: Seq[Int]): (Tensor, Rep[Array[Int]]) = {
      assert(this.nbDims == 4, "the input for maxPool (with batch) should have 4 dimensions")
      assert(kernels.size == 2 && strides.size == 2, "kernels and strides should be size 2")
      val (strideRow :: strideCol :: _) = strides.toList
      val (kernelRow :: kernelCol :: _) = kernels.toList
      assert(strideRow >= 1 && kernelRow >= 1, "kernel width and stride width should be at least 1")
      assert(strideCol >= 1 && kernelCol >= 1, "kernel height and stride height should be at least 1")
      assert(this.dims(2) >= kernelRow && this.dims(3) >= kernelCol, "Image too small for maxPool_k: " + this.dims + "|" + (kernelRow, kernelCol))

      val resWidth = convSize(this.dims(1), kernelRow, strideRow)
      val resHeight = convSize(this.dims(2), kernelCol, strideCol)
      val res = Tensor.fill(scala.Float.MinValue, this.dims(0), this.dims(1), resWidth, resHeight)
      val savedIdx = NewArray[Int](res.nbElem)

      for (i <- DataLoop(this.dims(0))) {
        val ptrInput  = slice(this.data, i * this.strides(1))
        val ptrOutput = slice(res.data, i * res.strides(1))
        val ptrIdx    = sliceI(savedIdx, i * res.strides(1))
        Tensor(ptrInput, this.dims.drop(1): _*).maxPool_k_inplace(
          kernelRow, kernelCol, strideRow, strideCol, Tensor(ptrOutput, res.dims.drop(1): _*), ptrIdx)
      }
      (res, savedIdx)
    }

    @virtualize
    def maxPool_k_inplace(kernelRow: Int, kernelCol: Int, strideRow: Int, strideCol: Int, res: Tensor, savedIdx: Rep[Array[Int]]): Unit = {
      val resWidth = res.dims(1)
      val resHeight = res.dims(2)

      // looping for the output
      val offout = var_new[Int](0)                              // offset of res, by channel  
      val offin  = var_new[Int](0)                              // offset of input, by channel
      for (outPane <- DataLoop(res.dims(0))) {
        val offout_1 = var_new[Int](offout)                     // offset of res, built on offout, by row
        val offin_1  = var_new[Int](offin)                      // offset of input, built on offin, by row
        for (outRow <- DataLoop(res.dims(1))) {
          val offout_2 = var_new[Int](offout_1)                 // offset of res, built on offout_1, by col
          val offin_2  = var_new[Int](offin_1)                  // offset of input, built on offin_1, by col
          for (outCol <- DataLoop(res.dims(2))) {

            // looping for the kernel
            val this_index_1 = var_new[Int](offin_2)            // offset of this (input) by row of kernel size
            for (dummy1 <- DataLoop(kernelRow)) {
              val this_index_2 = var_new[Int](this_index_1)     // offset of this (input), built on this_index_1, by col of kernel size
              for (dummy <- DataLoop(kernelCol)) {
                if (this.data(this_index_2) > res(offout_2)) {
                  res.data(offout_2) = this.data(this_index_2)
                  savedIdx(offout_2) = this_index_2
                } else ()
                this_index_2 += 1
              }
              this_index_1 += this.strides(2)
            }

            offout_2 += 1
            offin_2  += strideCol
          }
          offout_1 += res.strides(2)
          offin_1  += strideRow * this.strides(2)
        }
        offout += res.strides(1)
        offin  += this.strides(1)
      }
    }

    @virtualize
    def maxPool_k(kernels: Seq[Int], strides: Seq[Int]) = {

      assert(this.nbDims == 3, "the input for maxPool should have 3 dimensions")
      assert(kernels.size == 2 && strides.size == 2, "kernels and strides should be size 2 for maxpool_k")
      val (strideRow :: strideCol :: _) = strides.toList
      val (kernelRow :: kernelCol :: _) = kernels.toList
      assert(strideRow >= 1 && kernelRow >= 1, "kernel width and stride width should be at least 1")
      assert(strideCol >= 1 && kernelCol >= 1, "kernel height and stride height should be at least 1")
      assert(this.dims(1) >= kernelRow && this.dims(2) >= kernelCol, "Image too small for maxPool_k")

      val resWidth = convSize(this.dims(1), kernelRow, strideRow)
      val resHeight = convSize(this.dims(2), kernelCol, strideCol)
      val res = Tensor.fill(scala.Float.MinValue, this.dims(0), resWidth, resHeight)
      val savedIdx = NewArray[Int](res.nbElem)

      // looping for the output
      val offout = var_new[Int](0)                              // offset of res, by channel  
      val offin  = var_new[Int](0)                              // offset of input, by channel
      for (outPane <- DataLoop(res.dims(0))) {
        val offout_1 = var_new[Int](offout)                     // offset of res, built on offout, by row
        val offin_1  = var_new[Int](offin)                      // offset of input, built on offin, by row
        for (outRow <- DataLoop(res.dims(1))) {
          val offout_2 = var_new[Int](offout_1)                 // offset of res, built on offout_1, by col
          val offin_2  = var_new[Int](offin_1)                  // offset of input, built on offin_1, by col
          for (outCol <- DataLoop(res.dims(2))) {

            // looping for the kernel
            val this_index_1 = var_new[Int](offin_2)            // offset of this (input) by row of kernel size
            for (dummy1 <- DataLoop(kernelRow)) {
              val this_index_2 = var_new[Int](this_index_1)     // offset of this (input), built on this_index_1, by col of kernel size
              for (dummy <- DataLoop(kernelCol)) {
                if (this.data(this_index_2) > res(offout_2)) {
                  res.data(offout_2) = this.data(this_index_2)
                  savedIdx(offout_2) = this_index_2
                } else ()
                this_index_2 += 1
              }
              this_index_1 += this.strides(2)
            }

            offout_2 += 1
            offin_2  += strideCol
          }
          offout_1 += res.strides(2)
          offin_1  += strideRow * this.strides(2)
        }
        offout += res.strides(1)
        offin  += this.strides(1)
      }
      (res, savedIdx)
    }

    @virtualize
    def dropout(prob: Float = 0.5f) = {
      assert(0.0f <= prob && prob <= 1.0f)

      val res = NewArray[Float](this.nbElem)
      val mask = NewArray[Float](this.nbElem)

      val scale = if (prob < 1.0f) 1.0f / (1.0f - prob) else 0.0f

      val guard: Rep[Boolean] = prob < 1.0f
      for (i <- DataLoop(this.nbElem)) {
      // for (i <- 0 until this.nbElem: Rep[Range]) {
        if (guard && Random.rand() > prob) {
          res(i) = this.data(i) * scale
          mask(i) = scale
        } else {
          res(i) = 0.0f
          mask(i) = 0.0f
        }
      }

      (Tensor(res, this.dims.seq : _*), Tensor(mask, this.dims.seq : _*))
    }

    @virtualize
    def relu(inPlace: Boolean = false) = {
      assert(!inPlace)

      val res = NewArray[Float](this.nbElem)
      for (i <- 0 until this.nbElem: Rep[Range]) {
        if (this(i) < 0.0f)
          res(i) = 0.0f
        else
          res(i) = this.data(i)
      }

      Tensor(res, this.dims.seq : _*)
    }

    @virtualize
    def concat(dim: Int, others: Tensor*) = {
      assert(others.size >= 1, "there should be at least one tensor in others")
      assert(dim >= 0 && dim < this.nbDims, "dim should be within range of this.nbDims")
      assert(others.forall(x => x.nbDims == this.nbDims), "all tensors should have the same number of dimensions")
      assert(others.forall(x => (0 until this.nbDims: Range).forall(i =>  i == dim || x.dims(i) == this.dims(i))),
        "all dimensions except the concatenation dimension should be the same")

      // prepare result tensor
      val higherDims = this.dims.take(dim)
      val higherDimsSquashed = higherDims.fold(1)(_ * _)
      val resDims    = (0 until this.nbDims: Range).map{i => 
        if (i != dim) this.dims(i)
        else others.map(x => x.dims(dim)).fold(this.dims(dim))(_ + _)}
      val totalnbElem = resDims.fold(1)(_ * _)
      val res = NewArray[Float](totalnbElem)
      
      // prepare for looping/copying
      val totalFrom = this +: others        // put all tensors in one Seq for easy of handling
      val targetId = var_new(0)             // this is the index of res to write to
      // looping over dims higher than dim, squashed
      for (high <- DataLoop(higherDimsSquashed)) {
        // looping over the concatenation dim
        for (whichTensor <- totalFrom) {
          // looping over the dimensions lower than or equal to dim, in the current tensor
          val ptrIntput = slice(whichTensor.data, high * whichTensor.strides(dim))
          for (lowOrEqual <- DataLoop(whichTensor.strides(dim))) {
            res(targetId) = ptrIntput(lowOrEqual)
            targetId += 1
          }
        }
      }
      Tensor(res, resDims: _*)
    }

    @virtualize
    def global_ave_batch() = {
      assert(this.nbDims == 4, "assume this is Tensor with 4D (batch * channel * width * height")
      val resTensor = Tensor.zeros(this.dims.take(2): _*)
      
      val scale = this.strides(2)
      val offset = var_new(0)                      // offset of this, should step by this.strides(2)
      val res_offset = var_new(0)                  // offset of res, should step by 1
      // looping over each batch 
      for (batch <- DataLoop(this.dims(0))) {
        // looping over each channel
        for (channel <- DataLoop(this.dims(1))) {
          // get average of a channel
          val sum = var_new(0.0f)
          val offset_a = var_new(offset)           // offset of this for averaging, should step by this.strides(3)
          for (i <- DataLoop(this.dims(2))) {
            for (j <- DataLoop(this.dims(3))) {
              sum += this.data(offset_a + j)
            }
            offset_a += this.strides(3)
          }
          resTensor.data(res_offset) = sum / scale
          offset += this.strides(2)
          res_offset += 1
        }
      }
      resTensor
    }

    @virtualize
    def global_ave() = {
      // the result should be 1D (channel), by averaging the numbers in (width * height)
      assert(this.nbDims == 3, "assume this is Tensor with 3D (channel * width * height)")

      val res = NewArray[Float](this.dims(0))
      // looping over each channel
      for (channel <- DataLoop(this.dims(0))) {
        val offset = var_new(this.strides(1) * channel)
        val sum = var_new(0.0f)
        for (i <- DataLoop(this.dims(1))) {          
          for (j <- DataLoop(this.dims(2))) {
            sum += this.data(offset + j)
          }
          offset += this.strides(2)
        }
        res(channel) = sum / (this.strides(1))
      }
      Tensor(res, this.dims(0))
    }

    // FIXME: the MNIST example precomput the mean and std
    // I thought that normalize would need to compute it first and then
    // modify the data to match the one requested.
    // SO here what is expected is to have mean = 0 and std = 1 knowing that
    // the current mean is m and the current std is s
    @virtualize
    def normalize(m: Float, s: Float, inPlace: Boolean = false) = {
      assert(this.nbDims == 3 && this.dims(0) == 1) // FIXME
      if (inPlace) {
        this.mapInPlace(x => (x - m)/s)
        this
      } else {
        this.map(x => (x - m)/s)
      }
    }
  }

  object Tensor {

    def apply(dims: Int*) = {
      val size = dims.product
      new Tensor(NewArray[Float](size), dims)
    }
    def apply(data: Rep[Array[Float]], dims: Int*) = new Tensor(data, dims)

    def dimCompatible(a: Tensor, b: Tensor) = {
      (a.dims == b.dims) || a.isScalar || b.isScalar
    }

    def randseed(seed: Int) = unchecked[Unit]("srand(", seed, ")")
    def randseed() = unchecked[Unit]("srand(time(NULL))")
    def rand(dims: Int*) = randinit(dims.toSeq, 1.0f, None)
    def rand(scale: Float, dims: Int*) = randinit(dims.toSeq, scale, None)
    def randinit(dim0: Int): Tensor = randinit(NSeq(dim0), 1.0f, None)
    def randinit(dim0: Int, seed: Option[Int]): Tensor = randinit(NSeq(dim0), 1.0f, seed)
    def randinit(dim0: Int, dim1: Int, scale: Float): Tensor = randinit(NSeq(dim0, dim1), scale, None)
    def randinit(dims: NSeq[Int], scale: Float = 1.0f, seed: Option[Int] = None): Tensor = {
      val size = dims.product
      val res = NewArray[Float](size)
      for (i <- (0 until size): Rep[Range]) res(i) = (Random.rand() - 0.5f) * scale
      new Tensor(res, dims)
    }

    def randn(dim0: Int, dim1: Int = 1, scale: Float = 1.0f, offset: Int = 0) = {
      val res = NewArray[Float](dim0 * dim1)
      for (i <- (0 until dim0 * dim1): Rep[Range]) res(i) = unchecked[Float]("d(gen)") * scale
      Tensor(res, dim0, dim1)
    }

    def randPositive(dims: Int*) = {
      val size = dims.product
      val res = NewArray[Float](size)
      for (i <- (0 until size): Rep[Range]) res(i) = Random.rand()
      new Tensor(res, dims)
    }

    def fill(value: Rep[Float], dims: Int*) = {
      val size = dims.product
      val res = NewArray[Float](size)
      for (i <- (0 until size): Rep[Range]) res(i) = value
      new Tensor(res, dims)
    }

    def fill(fFill: NSeq[Rep[Int]] => Rep[Float], dims: Int*) = {
      val size = dims.product
      val res = NewArray[Float](size)

      var idx = var_new(0)
      def innerFill(args: NSeq[Rep[Int]]) = {
        res(idx) = fFill(args)
        idx += 1
      }


      val dum = (dims :\ innerFill _) {
        case (up, f) =>
          (args: NSeq[Rep[Int]]) => {
            for (i <- 0 until up: Rep[Range]) {
              f(args :+ i)
            }
          }
      }
      dum(NSeq[Rep[Int]]())
      new Tensor(res, dims)
    }

    def zeros(dims: Int*): Tensor = {
      fill(0.0f, dims: _*)
    }

    def zeros(that: Tensor): Tensor = {
      zeros(that.dims : _*)
    }

    def zeros_like(that: Tensor) = {
      zeros(that.dims : _*)
    }

    def scalar(value: Rep[Float]) = {
      val res = NewArray[Float](1)
      res(0) = value
      Tensor(res, 1)
    }

    def ones(dims: Int*) = fill(1.0f, dims: _*)
    def ones(that: Tensor) = fill(1.0f, that.dims: _*)
    def halves(dims: Int*) = fill(0.5f, dims: _*)

    def expand(vector: Tensor, dim1: Int) = {
      assert(vector.nbDims == 1)
      val res = NewArray[Float](vector.dims(0) * dim1)
      val off = var_new(0)
      for (j <- (0 until dim1): Rep[Range]){
        for (i <- (0 until vector.dims(0)): Rep[Range]) {
          res(off) = vector.data(i)
          off += 1
        }
      }
      new Tensor(res, dim1 +: vector.dims)
    }

    def copy(vector: Tensor) = {
      val res = NewArray[Float](vector.nbElem)
      for (i <- (0 until vector.nbElem): Rep[Range]) res(i) = vector.data(i)
      new Tensor(res, vector.dims)
    }

    def fromData(x: Float*) = {
      val y = x.toArray
      val res = NewArray[Float](y.length)
      for (i <- 0 until y.length: Range) res(i) = y(i)
      Tensor(res, y.length)
    }


    // def conv(that: Tensor, stride: (Int, Int) = (1, 1))

    @virtualize
    def assertEqual(a: Tensor, b: Tensor, mark: String = "", tal: Float = 0.000001f) = {
      assert(a.dims == b.dims, s"ERROR: $mark not equal in dimensionsi ${a.dims.seq} != ${b.dims.seq}\\n")

      val i = var_new(0)
      while (i < a.nbElem && { val diff = a.data(i) - b.data(i); diff > -tal && diff < tal }) {
        i += 1
      }
      if (i < a.nbElem)
        printf("ERROR: %s not equal in some data - %.4f != %.4f (%d)\\n", mark, a.data(i), b.data(i), i)
    }
  }


  // Tensor type is the similar to NumR, just replace RFloat with Tensor
  // also Tensor internally use array, which is mutable by default
  // so both field are val (not var) and can be updated by += -= *= /= setAsOne()
  // all instances of vectors will be shepherded by c++ smart pointers, alleviating the memory leak problem
  type diff = cps[Unit]

  class TensorR(val x: Tensor, val d: Tensor) extends Serializable {
    var isInput: Boolean = false // true if it is an input (no need to compute gradient)

    def clip_grad(bound: Float) = {
      d.clipAt(bound)
    }

    def + (that: TensorR): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x + that.x);
      k(y)
      generate_comment("backpropagate +")
      this.d += y.d; that.d += y.d
    }

    def - (that: TensorR): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x - that.x); k(y)
      //y.d.print("dot")
      this.d += y.d; that.d -= y.d
    }

    // this is element wise multiplication
    def * (that: TensorR): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x * that.x); k(y)
      // intermediate Tensors donot need to be substatiated, can optimize!
      //this.d += that.x * y.d; that.d += this.x * y.d;
      this.d.add_mult(that.x, y.d); that.d.add_mult(this.x, y.d)
    }

    // element wise division
    def / (that: TensorR): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x / that.x); k(y)
      // intermediate Tensors donot need to be substatiated, can optimize!
      //this.d += y.d / that.x
      this.d.add_div(y.d, that.x)
      //that.d -= this.x * y.d / (that.x * that.x)
      that.d.minus_mult_div_square(this.x, y.d, that.x)
    }

    // vector dot product or Matrix vector dot (viewed as multiple vector dot product) (not the common view)
    def dot(that: TensorR): TensorR @diff = shift { (k: TensorR => Unit) =>
      val res = x dot that.x
      val y = TensorR(res); k(y)
      // FIXME: intermediate Tensors donot need to be substatiated, can optimize!
      //y.d.print("dot")
      if (this.d.nbDims == 1) {
        assert(y.d.isScalar)
        this.d.addMul(y.d.data(0), that.x)
        that.d.addMul(y.d.data(0), this.x)
      } else {
        // FIXME: need optimization using addMul and dataloop!!
        this.d.add_cartesian(that.x, y.d)
        that.d.add_composion(this.x, y.d)
        //this.d.addMul(y.d.resize(y.d.dims(0), 1), that.x.resize(1, that.x.dims(0)))
        //that.d.resize(1, that.d.dims(0)).addMul(y.d.resize(1, y.d.dims(0)), this.x)
      }
      // this.d += that.x * y.d // broadcasting
      // that.d += this.x * y.d // broadcasting
    }

    def tanh(): TensorR @diff = shift { (k : TensorR => Unit) =>
      val y = TensorR(x.tanh()); k(y)
      // FIXME: intermediate Tensors donot need to be substatiated, can optimize!
      //this.d += (Tensor.ones(1) - y.x * y.x) * y.d // broadcasting
      generate_comment("backpropagate tanh")
      this.d.add_oneMinusSquare_mult(y.x, y.d)
    }

    def exp(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.exp()); k(y)
      // Fix
      //this.d += y.x * y.d
      generate_comment("backpropage exp")
      this.d.add_mult(y.x, y.d)
    }

    def log(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.log()); k(y)
      // Fix
      //this.d += y.d / x
      this.d.add_div(y.d, x)
    }

    def sigmoid(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.sigmoid()); k(y)
      //this.d += (Tensor.ones(1) - y.x) * y.x * y.d
      this.d.add_oneMinusThenMult_mult(y.x, y.d)
    }

    def sum(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = new TensorR(x.sum(), Tensor.zeros(1)); k(y)
      this.d += y.d
    }

    def logSoftmax(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.logSoftmax()); k(y)

      val s = y.d.sum().data(0)
      for (i <- 0 until y.x.nbElem: Rep[Range]) {
        this.d.data(i) += y.d.data(i) - Math.exp(y.x.data(i)).toFloat * s
      }
    }

    def logSoftmaxB(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.logSoftmaxB()); k(y)

      // back propagate
      val sum = y.d.sum2D(dim = 1)
      val offset = var_new(0)
      for (batch <- DataLoop(this.x.dims(0))) {
        for (i <- DataLoop(this.x.dims(1))) {
          this.d.data(offset) += y.d.data(offset) - Math.exp(y.x.data(offset)).toFloat * sum.data(batch)
          offset += 1
        }
      }
    }

    def resize(dims: Int*): TensorR @diff = shift { (k: TensorR => Unit) =>
      k(new TensorR(this.x.resize(dims : _*), this.d.resize(dims : _*)))
    }

    def nllLoss(target: Rep[Int]): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(x.nllLoss(target)); k(y)

      assert(y.x.isScalar)
      //y.d.print("nll")

      this.d.data(target) = -1.0f * y.d.data(0)
    }

    def update(lr: Float, mom: Float) = {
    }

    @virtualize
    // conv with batch, bias, and pads
    def convBBP(kernel: TensorR, bias: TensorR, strides: NSeq[Int], pads: Seq[Int]): TensorR@diff = shift { (k: TensorR => Unit) =>
      assert(this.isInput || this.d.nbElem == this.x.nbElem, "For convBBP, THIS is either input or intermediate stage")
      assert(this.x.nbDims == 4, "For convBBP, THIS is dim 4: batch, channel, row, col")
      assert(pads.tail.forall(x => x == pads.head), "pads should be the same in all directions")
      val y = TensorR(x conv2D_batch(kernel.x, bias.x, strides, pads))
      k(y)

      // back propagate
      val strideRow = strides.head
      val strideCol = strides.last

      if (pads.fold(0)((x: Int, y: Int) => x + y) == 0) {
        for (batch <- DataLoop(this.x.dims(0))) {
          val offOutputD = var_new(batch * y.d.strides(1))     // offset for the output, based on batch, step by 1
          val offKernel = var_new(0)                           // offset for kernel, step by kernel strides(1) -- which kernel
          // looping for the output
          for (kOut <- DataLoop(y.d.dims(1))) {
            val offInputR = var_new(batch * this.d.strides(1)) // offset of input, based on batch, step by input.strides(3) * strideRow
            val sum = var_new(0.0f)                            // collector of bias gradient
            for (row <- DataLoop(y.d.dims(2))) {
              val offInputC = var_new(offInputR)               // offset of input, based on offInputR, step by strideCol
              for (col <- DataLoop(y.d.dims(3))) {
                val dCurr: Rep[Float] = y.d.data(offOutputD)
                sum += dCurr                                   // collect bias gradient

                // looping for the kernel
                val offInputP = var_new(offInputC)             // offset of input, based on offInputC, step by input.strides(2)
                val offKernelR = var_new(offKernel)            // offset of kernel, based on offKernel, step by 1
                for (pane <- DataLoop(kernel.d.dims(1))) {
                  val offInputKR = var_new(offInputP)          // offset of input, step by input.strides(3) -- row
                  for (kR <- 0 until kernel.d.dims(2): Rep[Range]) {
                    for (kC <- 0 until kernel.d.dims(3): Rep[Range]) {
                      if (!this.isInput) this.d.data(offInputKR + kC) = this.d.data(offInputKR + kC) + dCurr * kernel.x.data(offKernelR)
                      kernel.d.data(offKernelR) = kernel.d.data(offKernelR) + dCurr * this.x.data(offInputKR + kC)
                      offKernelR += 1
                    }
                    offInputKR += this.x.strides(3)
                  }
                  offInputP += this.x.strides(2)
                }

                offInputC += strideCol
                offOutputD += 1
              }
              offInputR += strideRow * this.x.strides(3)
            }
            bias.d.data(kOut) = bias.d.data(kOut) + sum        // give value of collector to the bias gradient
            offKernel += kernel.x.strides(1)
          }
        }
      } else {
        for (batch <- DataLoop(this.x.dims(0))) {
          val offOutputD = var_new(batch * y.d.strides(1))     // offset for the output, based on batch, step by 1
          val offKernel  = var_new(0)                          // offset for the kernel, step by kernel strides(1) -- which kernel
          val offInputD  = batch * this.x.strides(1)           // fixed offset for the input, based on batch
          // looping for the output
          for (kOut <- DataLoop(y.d.dims(1))) {
            val InputR = var_new(-pads.head)                   // Row of input, starting from -pads
            val sum = var_new(0.0f)                            // collector of bias gradient
            for (row <- DataLoop(y.d.dims(2))) {
              val InputC = var_new(-pads.head)                 // Col of input, starting from -pads
              for (col <- DataLoop(y.d.dims(3))) {
                val dCurr: Rep[Float] = y.d.data(offOutputD)
                sum += dCurr                                   // collect the bias gradient

                // looping for the kernel
                val offKernelR = var_new(offKernel)            // offset if kernel, based on offKernel, step by 1
                // offset of input based on batch, pane, and index of output
                val InputI_pane = var_new[Int](offInputD + InputR * this.x.strides(3) + InputC) 
                for (pane <- DataLoop(kernel.d.dims(1))) {
                  val InputI_kR = var_new[Int](InputI_pane)    // offset of input based on InputI_pane and row
                  for (kR <- DataLoop(kernel.d.dims(2))) {
                    for (kC <- DataLoop(kernel.d.dims(3))) {
                      if (InputR+kR < 0 || InputR+kR >= this.x.dims(2) || InputC+kC < 0 || InputC+kC >= this.x.dims(3)) ()
                      else {
                        val InputI = InputI_kR + kC
                        if (!this.isInput) this.d.data(InputI) = this.d.data(InputI) + dCurr * kernel.x.data(offKernelR)
                        kernel.d.data(offKernelR) = kernel.d.data(offKernelR) + dCurr * this.x.data(InputI)
                        offKernelR += 1
                      }
                    }
                    InputI_kR += this.x.strides(3)
                  }
                  InputI_pane += this.x.strides(2)
                }

                InputC += strideCol
                offOutputD += 1
              }
              InputR += strideRow
            }
            bias.d.data(kOut) = bias.d.data(kOut) + sum
            offKernel += kernel.x.strides(1)
          }
        }
      }

      ()
    }

    @virtualize
    // conv with bias and pads
    def convBP(kernel: TensorR, bias: TensorR, strides: NSeq[Int], pads: NSeq[Int]): TensorR@diff = shift { (k: TensorR => Unit) =>
      
      assert(this.isInput || this.d.nbElem == this.x.nbElem)
      assert(pads.tail.forall(x => x == pads.head), "pads should be the same in all directions")
      val y = TensorR(x conv2D(kernel.x, bias.x, strides, pads))
      k(y)

      // back propagate
      val strideRow = strides.head
      val strideCol = strides.last

      if (pads.fold(0)((x: Int, y: Int) => x + y) == 0) {
        val offOutputD = var_new(0)                          // offset for the output, step by 1
        val offKernel = var_new(0)                           // offset for kernel, step by kernel strides(1) -- which kernel
        // looping for the output
        for (kOut <- 0 until y.d.dims(0): Rep[Range]) { 
          val offInputR = var_new(0)                         // offset of input, step by input.strides(2) * strideRow
          val sum = var_new(0.0f)                            // collector of bias gradient
          for (row <- 0 until y.d.dims(1): Rep[Range]) { 
            val offInputC = var_new(offInputR)               // offset of input, step by strideCol, based on offInputR
            for (col <- 0 until y.d.dims(2): Rep[Range]) {  
              val dCurr: Rep[Float] = y.d.data(offOutputD)
              sum += dCurr                                   // collect bias gradient

              // looping for the kernel
              val offInputP = var_new(offInputC)             // offset of input, step by input.strides(1), based on offInputC
              val offKernelR = var_new(offKernel)            // offset of kernel, step by 1, based on offKernel
              for (pane <- 0 until kernel.d.dims(1): Rep[Range]) {
                val offInputKR = var_new(offInputP)                  // offset of input, step by input.strides(2) -- row
                for (kR <- 0 until kernel.d.dims(2): Rep[Range]) {
                  for (kC <- 0 until kernel.d.dims(3): Rep[Range]) {
                    if (!this.isInput) this.d.data(offInputKR + kC) = this.d.data(offInputKR + kC) + dCurr * kernel.x.data(offKernelR)
                    kernel.d.data(offKernelR) = kernel.d.data(offKernelR) + dCurr * this.x.data(offInputKR + kC)
                    offKernelR += 1
                  }
                  offInputKR += this.x.strides(2)
                }
                offInputP += this.x.strides(1)
              }

              offInputC += strideCol
              offOutputD += 1
            }
            offInputR += strideRow * this.x.strides(2)
          }
          bias.d.data(kOut) = bias.d.data(kOut) + sum                            // give value of collector to the bias gradient
          offKernel += kernel.x.strides(1)
        }
      } else {
        val offOutputD = var_new(0)                          // offset for the output, step by 1
        val offKernel  = var_new(0)                          // offset for the kernel, step by kernel strides(1) -- which kernel
        // looping for the output
        for (kOut <- DataLoop(y.d.dims(0))) {
          val InputR = var_new(-pads.head)                   // Row of input, starting from -pads
          val sum = var_new(0.0f)                            // collector of bias gradient
          for (row <- DataLoop(y.d.dims(1))) {
            val InputC = var_new(-pads.head)                 // Col of input, starting from -pads
            for (col <- DataLoop(y.d.dims(2))) {
              val dCurr: Rep[Float] = y.d.data(offOutputD)
              sum += dCurr                                   // collect the bias gradient

              // looping for the kernel
              val offKernelR = var_new(offKernel)            // offset of kernel, step by 1, based on offKernel
              val InputI_pane = var_new[Int](InputR * this.x.strides(2) + InputC)  // offset of input based on pane and index of output
              for (pane <- DataLoop(kernel.d.dims(1))) {
                val InputI_kR = var_new[Int](InputI_pane)                          // offset of input based on InputI_pane and row
                for (kR <- DataLoop(kernel.d.dims(2))) {
                  for (kC <- DataLoop(kernel.d.dims(3))) {
                    if (InputR+kR < 0 || InputR+kR >= this.x.dims(1) || InputC+kC < 0 || InputC+kC >= this.x.dims(2)) ()
                    else {
                      val InputI = InputI_kR + kC                                  // offset of input based on pane and row and col
                      if (!this.isInput) this.d.data(InputI) = this.d.data(InputI) + dCurr * kernel.x.data(offKernelR)
                      kernel.d.data(offKernelR) = kernel.d.data(offKernelR) + dCurr * this.x.data(InputI)
                      offKernelR += 1  
                    }
                  }
                  InputI_kR += this.x.strides(2)
                }
                InputI_pane += this.x.strides(1)
              }

              InputC += strideCol
              offOutputD += 1 
            }
            InputR += strideRow
          }
          bias.d.data(kOut) = bias.d.data(kOut) + sum
          offKernel += kernel.x.strides(1)
        }
      }

      ()
    }

    @virtualize
    def conv(kernel: TensorR, strideRow: Int, strideCol: Int, tot: Rep[Array[Long]]): TensorR @diff = shift { (k: TensorR => Unit) =>
      assert(this.isInput || this.d.nbElem == this.x.nbElem)
      // val timer = Timer2()
      // timer.startTimer
      val y = TensorR(x conv2D(kernel.x, strideRow, strideCol))
      // tot(0) += timer.getElapsedTime
      k(y)
      //y.d.print("conv")

      // val timerBwd = Timer2()
      // TODO think about the loop order
      val offOutputD = var_new(0)                          // offset for the output, step by 1
      val offKernel = var_new(0)                           // offset for kernel, step by kernel strides(1) -- which kernel
      assert(y.d.dims(0) == kernel.x.dims(0))
      // timerBwd.startTimer
      
      // looping for the output
      for (kOut <- 0 until y.d.dims(0): Rep[Range]) { 
        val offInputR = var_new(0)                         // offset of input, step by input.strides(2) * strideRow
        for (row <- 0 until y.d.dims(1): Rep[Range]) { 
          val offInputC = var_new(offInputR)               // offset of input, step by strideCol, based on offInputR
          for (col <- 0 until y.d.dims(2): Rep[Range]) {  
            val dCurr: Rep[Float] = y.d.data(offOutputD)
            val offInputP = var_new(offInputC)             // offset of input, step by input.strides(1), based on offInputC
            val offKernelR = var_new(offKernel)            // offset of kernel, step by 1, based on offKernel
            
            // looping for the kernel
            for (pane <- 0 until kernel.d.dims(1): Rep[Range]) {
              val offInputKR = var_new(offInputP)                  // offset of input, step by input.strides(2) -- row
              for (kR <- 0 until kernel.d.dims(2): Rep[Range]) {
                for (kC <- 0 until kernel.d.dims(3): Rep[Range]) {
                  if (!this.isInput) this.d.data(offInputKR + kC) = this.d.data(offInputKR + kC) + dCurr * kernel.x.data(offKernelR)
                  kernel.d.data(offKernelR) = kernel.d.data(offKernelR) + dCurr * this.x.data(offInputKR + kC)
                  offKernelR += 1
                }
                offInputKR += this.x.strides(2)
              }
              offInputP += this.x.strides(1)
            }

            offInputC += strideCol
            offOutputD += 1
          }
          offInputR += strideRow * this.x.strides(2)
        }
        offKernel += kernel.x.strides(1)
      }
      // tot(1) += timerBwd.getElapsedTime
      ()
    }

    @virtualize  // maxpool with kernel size potentially different from strides, and works with batch dimension!
    def maxPoolBK(kernels: Seq[Int], strides: Seq[Int]): TensorR @diff = shift { (k: TensorR => Unit) => 
      val (y, sidx) = this.x.maxPool_k_batch(kernels, strides)
      val ty = TensorR(y)
      k(ty)

      // back propagate
      for (i <- DataLoop(y.nbElem)) {
        this.d.data(sidx(i)) += ty.d.data(i)
      }
    }

    @virtualize  // maxpool with kernel size potentially different from strides
    def maxPoolK(kernels: Seq[Int], strides: Seq[Int]): TensorR @diff = shift { (k: TensorR => Unit) =>
      val (y, sidx) = this.x.maxPool_k(kernels, strides)
      val ty = TensorR(y)
      k(ty)

      // back propagate
      for (i <- DataLoop(y.nbElem)) {
        this.d.data(sidx(i)) += ty.d.data(i)
      }

    }

    @virtualize
    def maxPool(strideRow: Int, strideCol: Int): TensorR @diff = shift { (k: TensorR => Unit) =>
      val (y, sidx) = this.x.maxPool(strideRow, strideCol)

      val ty = TensorR(y)
      k(ty)

      for (i <- 0 until y.nbElem: Rep[Range]) {
        this.d.data(sidx(i)) += ty.d.data(i)
      }
    }

    @virtualize
    def concat(dim: Int, others: TensorR*): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = this.x.concat(dim, others.map(t => t.x): _*)
      val ty = TensorR(y)
      k(ty)

      // back propagate
      val higherDims = this.x.dims.take(dim)
      val higherDimsSquashed = higherDims.fold(1)(_ * _)

      val totalFrom = this +: others   // put all tensorRs in one Seq for easy handling
      val targetId = var_new(0)        // this is the index of res to read gradient from
      // looping over dims higher than dim, squashed
      for (high <- DataLoop(higherDimsSquashed)) {
        // looping over the concatenation dim
        for (whichTensorR <- totalFrom) {
          // looping over the dimensions lower than or equal to dim (but within an input tensor)
          val ptrInput = slice(whichTensorR.d.data, high * whichTensorR.x.strides(dim))
          for (lowOrEqual <- DataLoop(whichTensorR.x.strides(dim))) {
            ptrInput(lowOrEqual) += ty.d.data(targetId)
            targetId += 1
          }
        }
      }
    }

    @virtualize
    def global_ave_batch(): TensorR @diff = shift { k: (TensorR => Unit) =>
      val y = this.x.global_ave_batch()
      val ty = TensorR(y)
      k(ty)
      
      // back propagate
      val scale = 1.0f / this.x.strides(2)
      val offset = var_new(0)                      // offset of this, should step by this.x.strides(2)
      val res_offset = var_new(0)                  // offset of res, should step by 1
      // looping over each batch 
      for (batch <- DataLoop(this.x.dims(0))) {
        // looping over each channel
        for (channel <- DataLoop(this.x.dims(1))) {
          // reflect gradient of ty to this, by scale
          val offset_a = var_new(offset)           // offset of this, should step by this.x.strides(3)
          for (i <- DataLoop(this.x.dims(2))) {
            for (j <- DataLoop(this.x.dims(3))) {
              this.d.data(offset_a + j) += ty.d(res_offset) * scale
            }
            offset_a += this.x.strides(3)
          }
          offset += this.x.strides(2)
          res_offset += 1
        }
      }
    }

    @virtualize
    def dropout(prob: Float): TensorR @diff = shift { (k: TensorR => Unit) =>
      val (y, noise) = this.x.dropout(prob)
      val ty = TensorR(y)

      k(ty)

      this.d += noise * ty.d
    }

    @virtualize
    def relu(): TensorR @diff = shift { (k: TensorR => Unit) =>
      val y = TensorR(this.x.relu(false))
      k(y)

      //y.d.print("relu")

      for (i <- 0 until this.x.nbElem: Rep[Range]) {
        this.d.data(i) = if (this.x.data(i) < 0.0f) 0.0f else y.d.data(i)
      }
    }

    def print(msg: String = "", derivative: Boolean = false): Unit = {
      this.x.print(msg)
      if (derivative) {
        if (msg == "")
          printf("=========\\n")
        this.d.print(s"derivative $msg")
      }
    }

    def clear_all() = {
      x.clear()
      d.clear()
    }

    def clear_grad() = {
      d.clear()
    }
  }

  object TensorR {
    def apply(a: Tensor, isInput: Boolean = false): TensorR = {
      val d = if (isInput) Tensor.scalar(0.0f) else Tensor.zeros_like(a)
      val res = new TensorR(a, d)
      res.isInput = isInput
      res
    }
    def apply(a: Rep[Array[Float]], dim0: Int, dim1: Int): TensorR = {
      new TensorR(Tensor(a, dim0, dim1), Tensor.zeros(dim0, dim1))
    }

    def apply(dim0: Int, dim1: Int): TensorR = {
      new TensorR(Tensor.zeros(dim0, dim1), Tensor.zeros(dim0, dim1))
    }

  }

  // change fun signature for memory leak issue (no more returning of array, just update the array provided by the caller)
  // this is in accordance of the destination-passing style
  // the fun take array[array[double]] of size 2, with the first array to be the x, and the second array to be the d
  def FUNc(f: TensorR => Unit): (TensorR => Unit) = {
    (x:TensorR) => {
      val dims = x.x.dims.toSeq
      val f1 = fun { (x: Rep[Array[Array[Float]]]) =>
        val tensor = new TensorR(Tensor(x(0), dims: _*), Tensor(x(1), dims: _*))
        f(tensor)
      };

      val in = NewArray[Array[Float]](2)
      in(0) = x.x.data; in(1) = x.d.data
      f1(in) // f1 should take Array[Array[Float]] and update the gradient of x
    }
  }

  def RST(a: => Unit @diff) = continuations.reset {
    a;
    ()
  }

  @virtualize
  def IF(c: Rep[Boolean])(a: =>TensorR @diff)(b: =>TensorR @diff): TensorR @diff = shift { k:(TensorR => Unit) =>
    val k1 = FUNc(k)

    if (c) RST(k1(a)) else RST(k1(b))
  }

  @virtualize
  def LOOP(init: TensorR)(c: TensorR => Rep[Boolean])(b: TensorR => TensorR @diff): TensorR @diff = shift { k:(TensorR => Unit) =>
    // val k1 = FUN(init.x.dims(0))(k)

    lazy val loop: TensorR => Unit = FUNc { (x: TensorR) =>
      if (c(x)) RST(loop(b(x))) else RST(k(x))
    }
    loop(init)
  }

  def FUNs(f: Rep[Int] => TensorR => Unit): (Rep[Int] => TensorR => Unit) = {
    (i: Rep[Int]) => (x:TensorR) => {
      val dims = x.x.dims.toSeq
      val f1 = fun { (i: Rep[Int], x: Rep[Array[Array[Float]]]) => 
        val tensor = new TensorR(Tensor(x(0), dims: _*), Tensor(x(1), dims: _*))
        f(i)(tensor)
      };    

      val in = NewArray[Array[Float]](2)
      in(0) = x.x.data; in(1) = x.d.data
      f1(i, in)
    }
  }

  @virtualize
  def LOOPS(init: TensorR)(c: Rep[Int])(b: Rep[Int] => TensorR => TensorR @diff): TensorR @diff = shift { k:(TensorR => Unit) =>
    lazy val loop: Rep[Int] => TensorR => Unit = FUNs { (i: Rep[Int]) => (x: TensorR) =>
      if (i < c) { RST(loop(i+1)(b(i)(x))) } else RST(k(x))
    }
    loop(0)(init)
  }

  def FUNsm(f: Rep[Int] => ArrayBuffer[TensorR] => Unit): (Rep[Int] => ArrayBuffer[TensorR] => Unit) = {
    (i: Rep[Int]) => (x:ArrayBuffer[TensorR]) => {
      val dims = x.map(_.x.dims.seq)
      val f1 = fun { (i: Rep[Int], x: Rep[Array[Array[Float]]]) =>
        val tensors = ArrayBuffer[TensorR]()
        for (u <- (0 until dims.length): Range) {
          tensors.append(new TensorR(Tensor(x(u*2), dims(u) : _*), Tensor(x(u*2+1), dims(u) : _*)))
        }
        f(i)(tensors)
      };

      val in = NewArray[Array[Float]](2 * dims.length)
      for (u <- (0 until dims.length): Range) {
        in(u*2) = x(u).x.data; in(u*2+1) = x(u).d.data
      }
      f1(i, in)
    }
  }

  @virtualize
  def LOOPSM(init: ArrayBuffer[TensorR])(c: Rep[Int])(b: Rep[Int] => ArrayBuffer[TensorR] => ArrayBuffer[TensorR] @diff):
  ArrayBuffer[TensorR] @diff = shift { k: (ArrayBuffer[TensorR] => Unit) =>
    lazy val loop: Rep[Int] => ArrayBuffer[TensorR] => Unit = FUNsm { (i: Rep[Int]) => (x: ArrayBuffer[TensorR]) =>
      if (i < c) {
        RST(loop(i+1)(b(i)(x)))
      } else {
        RST(k(x))
      }
    }
    loop(0)(init)
  }

  def FUNl(dim0: Int)(f: (Rep[Int] => (TensorR => Unit) => (TensorR => Unit))): (Rep[Int] => (TensorR => Unit) => (TensorR => Unit)) = {

    val f1 = fun { (i:  Rep[Int], t1: Rep[Array[Array[Float]] => Unit], xx: Rep[Array[Array[Float]]]) => 
      val t2: (TensorR => Unit) = { (x:TensorR) =>
        val temp = NewArray[Array[Float]](2)
        temp(0) = x.x.data; temp(1) = x.d.data
        t1(temp)
      }
      val t3: (TensorR => Unit) = f(i)(t2)
      t3(new TensorR(Tensor(xx(0), dim0), Tensor(xx(1), dim0)))
    }

    {i: Rep[Int] => k1: (TensorR => Unit) =>
      {
        val k2: Rep[Array[Array[Float]] => Unit] = fun { (x: Rep[Array[Array[Float]]]) =>
          k1(new TensorR(Tensor(x(0), dim0), Tensor(x(1), dim0)))
        }
        val k4: (TensorR => Unit) = {(x: TensorR) =>
          val temp = NewArray[Array[Float]](2)
          temp(0) = x.x.data; temp(1) = x.d.data
          f1(i, k2, temp)
        }
        k4
      }
    }
  }

  @virtualize
  def LOOPL(init: TensorR)(c: Rep[Int])(b: Rep[Int] => TensorR => TensorR @diff): TensorR @diff = shift { k: (TensorR => Unit) =>
    lazy val loop: Rep[Int] => (TensorR => Unit) => TensorR => Unit = FUNl(init.x.dims(0)){ (gc: Rep[Int]) => (k: TensorR => Unit) => (x: TensorR) =>
      if (gc < c) { loop(gc+1)((x: TensorR) => RST(k(b(gc)(x))))(x) } else { RST(k(x)) }
    }
    loop(0)(k)(init)
  }

  @virtualize
  def LOOPT(init: TensorR)(lch: Rep[Array[Int]], rch: Rep[Array[Int]])(b: (TensorR, TensorR, Rep[Int]) => TensorR @diff): TensorR @diff = shift {
    k: (TensorR => Unit) =>

      lazy val tree: Rep[Int] => (TensorR => Unit) => TensorR => Unit = FUNl(init.x.dims(0)){ (i: Rep[Int]) => (k: TensorR => Unit) => (x: TensorR) =>
        if (i >= 0) { tree(lch(i))((l: TensorR) => tree(rch(i))((r: TensorR) => RST(k(b(l, r, i))))(x))(x) } else { RST(k(x)) }
      }
      tree(0)(k)(init)
  }

  def FUNlm(dim0s: ArrayBuffer[Int])(f: (Rep[Int] => (ArrayBuffer[TensorR] => Unit) => (ArrayBuffer[TensorR] => Unit))):
  (Rep[Int] => (ArrayBuffer[TensorR] => Unit) => (ArrayBuffer[TensorR] => Unit)) = {
    val length = dim0s.length
    val f1 = fun { (i: Rep[Int], t1: Rep[Array[Array[Float]] => Unit], xx: Rep[Array[Array[Float]]]) => 
      val t2: (ArrayBuffer[TensorR] => Unit) = { (x: ArrayBuffer[TensorR]) =>
        val aa = NewArray[Array[Float]](2*length)
        for (u <- (0 until length): Range) {
          aa(u*2) = x(u).x.data; aa(u*2+1) = x(u).d.data
        }
        t1(aa)
      }
      val t3: (ArrayBuffer[TensorR] => Unit) = f(i)(t2)
      val tensors = ArrayBuffer[TensorR]()
      for (u <- (0 until length): Range) {
        tensors.append(new TensorR(Tensor(xx(u*2), dim0s(u)), Tensor(xx(u*2+1), dim0s(u))))
      }
      t3(tensors)
    };

    {i: Rep[Int] => k1: (ArrayBuffer[TensorR] => Unit) =>
      {
        val k2: Rep[Array[Array[Float]] => Unit] = fun { (x: Rep[Array[Array[Float]]]) =>
          val tensors = ArrayBuffer[TensorR]()
          for (u <- (0 until length): Range) {
            tensors.append(new TensorR(Tensor(x(u*2), dim0s(u)), Tensor(x(u*2+1), dim0s(u))))
          }
          k1(tensors)
        }
        val k4: (ArrayBuffer[TensorR] => Unit) = {(x: ArrayBuffer[TensorR]) =>
          val arrays = NewArray[Array[Float]](2*length)
          for (u <- (0 until length): Range) {
            arrays(u*2) = x(u).x.data; arrays(u*2+1) = x(u).d.data
          }
          f1(i, k2, arrays)
        }
        k4
      }
    }
  }

  @virtualize
  def LOOPLM(init: ArrayBuffer[TensorR])(c: Rep[Int])(b: Rep[Int] => ArrayBuffer[TensorR] => ArrayBuffer[TensorR] @diff):
  ArrayBuffer[TensorR] @diff = shift { k: (ArrayBuffer[TensorR] => Unit) =>
    lazy val loop: Rep[Int] => (ArrayBuffer[TensorR] => Unit) => ArrayBuffer[TensorR] => Unit = FUNlm(init map (_.x.dims(0))) {
      (i: Rep[Int]) => (k: ArrayBuffer[TensorR] => Unit) => (x: ArrayBuffer[TensorR]) =>
        if (i < c) { loop(i+1)((x: ArrayBuffer[TensorR]) => RST(k(b(i)(x))))(x) } else { RST(k(x)) }
    }
    loop(0)(k)(init)
  }

  @virtualize
  def LOOPTM(init: ArrayBuffer[TensorR])(lch: Rep[Array[Int]], rch: Rep[Array[Int]])
  (b: (ArrayBuffer[TensorR], ArrayBuffer[TensorR], Rep[Int]) => ArrayBuffer[TensorR] @diff): ArrayBuffer[TensorR] @diff = shift {
    k: (ArrayBuffer[TensorR] => Unit) =>

      lazy val tree: Rep[Int] => (ArrayBuffer[TensorR] => Unit) => ArrayBuffer[TensorR] => Unit = FUNlm(init.map(_.x.dims(0))) {
        (i: Rep[Int]) => (k: ArrayBuffer[TensorR] => Unit) => (x: ArrayBuffer[TensorR]) =>
          if (i >= 0) { tree(lch(i))((l: ArrayBuffer[TensorR]) => tree(rch(i))((r: ArrayBuffer[TensorR]) => RST(k(b(l, r, i))))(x))(x) }
          else { RST(k(x)) }
      }
      tree(0)(k)(init)
  }

  def gradR(f: TensorR => TensorR @diff)(x: Tensor): Tensor = {
    val x1 = new TensorR(x, Tensor.zeros(x.dims(0)))
    reset { val y = f(x1)
      y.d.setAsOne()
      // y.x.print() // this is the result of forward propagation (likely the loss)
    () }
    x1.d
  }

  // same as gradR function, except that we return the final result of f, not the gradient of input
  // gradient of input is supposed to be dummy value here
  // gradient of useful tensors are in closure, and can be accessed directly from outside of this function
  def gradR_loss(f: TensorR => TensorR @diff)(x: Tensor): Tensor = {
    val x1 = TensorR(x) // this should be a dummy tensor
    val result = Tensor.zeros(1)                  // this should be the loss
    reset {
      val y = f(x1)
      y.d.setAsOne()
      result.copy_data(y.x)
      //y.x.print()
    () }
    result
  }

  def getMallocAddr(): Rep[Long] = {
    unchecked[Long]("(long)mallocAddr")
  }

  def resetMallocAddr(addr: Rep[Long]) = {
    unchecked[Unit]("mallocAddr = (void*)", addr)
  }

}
