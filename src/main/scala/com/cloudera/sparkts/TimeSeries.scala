/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.sparkts

import java.time._
import breeze.linalg.{diff, DenseMatrix => BDM, DenseVector => BDV}
import org.apache.spark.mllib.linalg.{DenseMatrix, Vector}

import MatrixUtil._
import TimeSeries._

import scala.reflect.ClassTag

class TimeSeries[K](val index: DateTimeIndex, val data: DenseMatrix,
                    val keys: Array[K])(implicit val kClassTag: ClassTag[K])
  extends Serializable {

  private def dataToBreeze: BDM[Double] = data

  /**
   * IMPORTANT: this function assumes that the DateTimeIndex is a UniformDateTimeIndex, not an
   * Irregular one.
   *
   * Lags all individual time series of the TimeSeries instance by up to maxLag amount.
   * The lagged time series has its keys generated by the laggedKey function which takes
   * two input parameters: the original key and the lag order, and should return a
   * corresponding lagged key.
   *
   * Example input TimeSeries:
   *   time   a   b
   *   4 pm   1   6
   *   5 pm   2   7
   *   6 pm   3   8
   *   7 pm   4   9
   *   8 pm   5   10
   *
   * With maxLag 2, includeOriginals = true and TimeSeries.laggedStringKey, we would get:
   *   time   a   lag1(a)   lag2(a)  b   lag1(b)  lag2(b)
   *   6 pm   3   2         1         8   7         6
   *   7 pm   4   3         2         9   8         7
   *   8 pm   5   4         3         10  9         8
   *
   */
  def lags[U: ClassTag](maxLag: Int, includeOriginals: Boolean, laggedKey: (K, Int) => U)
    : TimeSeries[U] = {
    val numCols = maxLag * keys.length + (if (includeOriginals) keys.length else 0)
    val numRows = data.numRows - maxLag

    val dataBreeze = dataToBreeze
    val laggedDataBreeze = new BDM[Double](numRows, numCols)
    (0 until data.numCols).foreach { colIndex =>
      val offset = maxLag + (if (includeOriginals) 1 else 0)
      val start = colIndex * offset

      Lag.lagMatTrimBoth(dataBreeze(::, colIndex), laggedDataBreeze, maxLag, includeOriginals,
        start)
    }

    val newKeys = keys.indices.map { keyIndex =>
      val key = keys(keyIndex)
      val lagKeys = (1 to maxLag).map(lagOrder => laggedKey(key, lagOrder)).toArray[U]

      if (includeOriginals) Array(laggedKey(key, 0)) ++ lagKeys else lagKeys
    }.reduce((prev: Array[U], next: Array[U]) => prev ++ next)

    val newDatetimeIndex = index.islice(maxLag, data.rows)

    new TimeSeries[U](newDatetimeIndex, laggedDataBreeze, newKeys.asInstanceOf[Array[U]])
  }

  /**
   * This is equivalent to lags(maxLag, includeOriginals, TimeSeries.laggedPairKey _).
   * It returns TimeSeries with a new key that is a pair of (original key, lag order).
   *
   */
  def lags[U >: (K, Int)](maxLag: Int, includeOriginals: Boolean): TimeSeries[(K, Int)] =
    lags(maxLag, includeOriginals, laggedPairKey[K])

  /**
   * IMPORTANT: this function assumes that the DateTimeIndex is a UniformDateTimeIndex, not an
   * Irregular one.
   *
   * Lags the specified individual time series of the TimeSeries instance by up to their matching
   * lag amount. Each time series can be indicated to either retain the original value, or drop it.
   *
   * In other words, the lagsPerCol has the following structure:
   *
   *    ("variableName1" -> (keepOriginalValue, maxLag),
   *     "variableName2" -> (keepOriginalValue, maxLag),
   *     ...)
   *
   * See description of the above lags function for an example of the lagging process.
   */
  def lags[U: ClassTag](lagsPerCol: Map[K, (Boolean, Int)], laggedKey: (K, Int) => U)
    : TimeSeries[U] = {
    val maxLag = lagsPerCol.map(_._2._2).max
    val numCols = lagsPerCol.map(pair => pair._2._2 + (if (pair._2._1) 1 else 0)).sum
    val numRows = data.rows - maxLag

    val dataBreeze = dataToBreeze
    val laggedDataBreeze = new BDM[Double](numRows, numCols)

    var curStart = 0
    keys.indices.zip(keys).foreach { indexKeyPair =>
      val colIndex = indexKeyPair._1
      val curLag = lagsPerCol(indexKeyPair._2)._2
      val curInclude = lagsPerCol(indexKeyPair._2)._1
      val offset = curLag + (if (curInclude) 1 else 0)

      Lag.lagMatTrimBoth(dataBreeze(::, colIndex), laggedDataBreeze, curLag, curInclude, curStart)

      curStart += offset
    }

    val newKeys: Array[U] = keys.indices.map(keyIndex => {
      val key = keys(keyIndex)

      var lagKeys = Array[U]()
      if (lagsPerCol.contains(key)) {
        lagKeys = (1 to lagsPerCol(key)._2).map(lagOrder => laggedKey(key, lagOrder)).toArray
      }

      if (lagsPerCol(key)._1) {
        Array(laggedKey(key, 0)) ++ lagKeys
      } else {
        lagKeys
      }
    }).reduce(_ ++ _)

    // This assumes the datetimeindex's 0 index represents the oldest data point
    val newIndex = index.islice(maxLag, data.rows)

    new TimeSeries[U](newIndex, laggedDataBreeze, newKeys.asInstanceOf[Array[U]])
  }

  /**
   * This is equivalent to lags(lagsPerCol, TimeSeries.laggedPairKey _).
   * It returns TimeSeries with a new key that is a pair of (original key, lag order).
   *
   */
  def lags[U >: (K, Int)](lagsPerCol: Map[K, (Boolean, Int)])
    : TimeSeries[(K, Int)] = {
    lags(lagsPerCol, laggedPairKey[K]_)
  }

  def slice(range: Range): TimeSeries[K] = {
    new TimeSeries[K](index.islice(range), dataToBreeze(range, ::), keys)
  }

  def union(vec: Vector, key: K): TimeSeries[K] = {
    val mat = BDM.zeros[Double](data.rows, data.cols + 1)
    (0 until data.cols).foreach(c => mat(::, c to c) := dataToBreeze(::, c to c))
    mat(::, -1 to -1) := toBreeze(vec)
    new TimeSeries[K](index, mat, keys :+ key)
  }

  /**
   * Returns a TimeSeries where each time series is differenced with the given order. The new
   * TimeSeries will be missing the first n date-times.
   */
  def differences(lag: Int): TimeSeries[K] = {
    mapSeries(index.islice(lag, index.size), vec => diff(toBreeze(vec).toDenseVector, lag))
  }

  /**
   * Returns a TimeSeries where each time series is differenced with order 1. The new TimeSeries
   * will be missing the first date-time.
   */
  def differences(): TimeSeries[K] = differences(1)

  /**
   * Returns a TimeSeries where each time series is quotiented with the given order. The new
   * TimeSeries will be missing the first n date-times.
   */
  def quotients(lag: Int): TimeSeries[K] = {
    mapSeries(index.islice(lag, index.size), vec => UnivariateTimeSeries.quotients(vec, lag))
  }

  /**
   * Returns a TimeSeries where each time series is quotiented with order 1. The new TimeSeries will
   * be missing the first date-time.
   */
  def quotients(): TimeSeries[K] = quotients(1)

  /**
   * Returns a return series for each time series. Assumes periodic (as opposed to continuously
   * compounded) returns.
   */
  def price2ret(): TimeSeries[K] = {
    mapSeries(index.islice(1, index.size), vec => UnivariateTimeSeries.price2ret(vec, 1))
  }

  def univariateSeriesIterator(): Iterator[Vector] = {
    new Iterator[Vector] {
      var i = 0
      def hasNext: Boolean = i < data.cols
      def next(): Vector = {
        i += 1
        dataToBreeze(::, i - 1)
      }
    }
  }

  def univariateKeyAndSeriesIterator(): Iterator[(K, Vector)] = {
    new Iterator[(K, Vector)] {
      var i = 0
      def hasNext: Boolean = i < data.cols
      def next(): (K, Vector) = {
        i += 1
        (keys(i - 1), dataToBreeze(::, i - 1))
      }
    }
  }

  def toInstants(): IndexedSeq[(ZonedDateTime, Vector)] = {
    (0 until data.rows).map(rowIndex => (index.dateTimeAtLoc(rowIndex),
      fromBreeze(dataToBreeze(rowIndex, ::).inner.toVector)))
  }

  /**
   * Applies a transformation to each series that preserves the time index.
   */
  def mapSeries(f: (Vector) => Vector): TimeSeries[K] = {
    mapSeries(index, f)
  }

  /**
   * Applies a transformation to each series that preserves the time index. Passes the key along
   * with each series.
   */
  def mapSeriesWithKey(f: (K, Vector) => Vector): TimeSeries[K] = {
    val newData = new BDM[Double](index.size, data.cols)
    univariateKeyAndSeriesIterator().zipWithIndex.foreach { case ((key, series), i) =>
      newData(::, i) := toBreeze(f(key, series))
    }
    new TimeSeries[K](index, newData, keys)
  }

  /**
   * Applies a transformation to each series such that the resulting series align with the given
   * time index.
   */
  def mapSeries(newIndex: DateTimeIndex, f: (Vector) => Vector): TimeSeries[K] = {
    val newSize = newIndex.size
    val newData = new BDM[Double](newSize, data.cols)
    univariateSeriesIterator().zipWithIndex.foreach { case (vec, i) =>
      newData(::, i) := toBreeze(f(vec))
    }
    new TimeSeries[K](newIndex, newData, keys)
  }

  def mapValues[U](f: (Vector) => U): Seq[(K, U)] = {
    univariateKeyAndSeriesIterator().map(ks => (ks._1, f(ks._2))).toSeq
  }

  /**
   * Gets the first univariate series and its key.
   */
  def head(): (K, Vector) = univariateKeyAndSeriesIterator().next()

  /**
   * Returns a TimeSeries with each univariate series resampled to a new date-time index. Resampling
   * provides flexible semantics for specifying which date-times in each input series correspond to
   * which date-times in the output series, and for aggregating observations when downsampling.
   *
   * Based on the closedRight and stampRight parameters, resampling partitions time into non-
   * overlapping intervals, each corresponding to a date-time in the target index. Each resulting
   * value in the output series is determined by applying an aggregation function over all the
   * values that fall within the corresponding window in the input series. If no values in the
   * input series fall within the window, a NaN is used.
   *
   * Compare with the equivalent functionality in Pandas:
   * http://pandas.pydata.org/pandas-docs/stable/generated/pandas.DataFrame.resample.html
   *
   * @param targetIndex The date-time index of the resulting series.
   * @param aggr Function for aggregating multiple points that fall within a window.
   * @param closedRight If true, the windows are open on the left and closed on the right. Otherwise
   *                    the windows are closed on the left and open on the right.
   * @param stampRight If true, each date-time in the resulting series marks the end of a window.
   *                   This means that all observations after the end of the last window will be
   *                   ignored. Otherwise, each date-time in the resulting series marks the start of
   *                   a window. This means that all observations after the end of the last window
   *                   will be ignored.
   * @return The values of the resampled series.
   */

  def resample(
      targetIndex: DateTimeIndex,
      aggr: (Array[Double], Int, Int) => Double,
      closedRight: Boolean,
      stampRight: Boolean): TimeSeries[K] = {
    mapSeries(targetIndex, Resample.resample(_, index, targetIndex, aggr, closedRight, stampRight))
  }
}

object TimeSeries {
  def laggedStringKey(key: String, lagOrder: Int): String =
    if (lagOrder > 0) s"lag${lagOrder}($key)" else key

  def laggedPairKey[K](key: K, lagOrder: Int): (K, Int) = (key, lagOrder)

  def timeSeriesFromIrregularSamples[K](
      samples: Seq[(ZonedDateTime, Array[Double])],
      keys: Array[K],
      zone: ZoneId = ZoneId.systemDefault())
      (implicit kClassTag: ClassTag[K])
    : TimeSeries[K] = {
    val mat = new BDM[Double](samples.length, samples.head._2.length)
    val dts: Array[Long] = samples.map(pair => TimeSeriesUtils.zonedDateTimeToLong(pair._1)).toArray
    for (i <- samples.indices) {
      val (_, values) = samples(i)
      mat(i to i, ::) := new BDV[Double](values)
    }
    new TimeSeries[K](new IrregularDateTimeIndex(dts, zone), mat, keys)
  }

  /**
   * This function should only be called when you can safely make the assumption that the time
   * samples are uniform (monotonously increasing) across time.
   */
  def timeSeriesFromUniformSamples[K](
      samples: Seq[Array[Double]],
      index: UniformDateTimeIndex,
      keys: Array[K])
     (implicit kClassTag: ClassTag[K]): TimeSeries[K] = {
    val mat = new BDM[Double](samples.length, samples.head.length)

    for (i <- samples.indices) {
      mat(i to i, ::) := new BDV[Double](samples(i))
    }
    new TimeSeries[K](index, mat, keys)
  }

  def timeSeriesFromVectors[K](
      vectors: Iterable[Vector],
      index: DateTimeIndex,
      keys: Array[K])
      (implicit kClassTag: ClassTag[K])
    : TimeSeries[K] = {
    val mat = new BDM[Double](index.size, vectors.size)

    var i = 0
    for (series <- vectors) {
      mat(::, i to i) := toBreeze(series)
      i += 1
    }

    new TimeSeries[K](index, mat, keys)
  }
}

trait TimeSeriesFilter extends Serializable {
  /**
   * Takes a time series of i.i.d. observations and filters it to take on this model's
   * characteristics.
   * @param ts Time series of i.i.d. observations.
   * @param dest Array to put the filtered time series, can be the same as ts.
   * @return the dest param.
   */
  def filter(ts: Array[Double], dest: Array[Double]): Array[Double]
}
