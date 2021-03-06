// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import scala.reflect.ClassTag
import org.apache.spark._
import org.apache.spark.ml._
import org.apache.spark.sql._
import org.scalatest._
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import org.scalactic.source.Position

// Common test tags
object TestBase {
  object Extended extends Tag("com.microsoft.ml.spark.test.tags.extended")
  object LinuxOnly extends Tag("com.microsoft.ml.spark.test.tags.linuxonly")
}

trait LinuxOnly extends TestBase {
  override def test(testName: String, testTags: Tag*)(testFun: => Any)(implicit pos: Position): Unit =
    super.test(testName, testTags.toList.::(TestBase.LinuxOnly): _*)(testFun)
}

abstract class TestBase extends FunSuite with BeforeAndAfterEachTestData with BeforeAndAfterAll {

  println(s"\n>>>-------------------- $this --------------------<<<")

  // "This Is A Bad Thing" according to my research. However, this is
  // just for tests so maybe ok. A better design would be to break the
  // session stuff into TestSparkSession as a trait and have test suites
  // that need it "with TestSparkSession" instead, but that's a lot of
  // changes right now and maybe not desired.
  private var sessionInitialized = false
  protected lazy val session: SparkSession = {
    info(s"Creating a spark session for suite $this")
    sessionInitialized = true
    SparkSessionFactory
      .getSession(s"$this", logLevel = "WARN")
  }

  protected lazy val sc: SparkContext = session.sparkContext
  protected lazy val dir = SparkSessionFactory.workingDir
  protected def normalizePath(path: String) = SparkSessionFactory.customNormalize(path)

  // Timing info
  var suiteElapsed: Long = 0
  var testStart: Long = 0
  var testElapsed: Long = 0

  // Test Fixture Overrides
  protected override def beforeEach(td: TestData): Unit = {
    testStart = System.currentTimeMillis
    testElapsed = 0
    super.beforeEach(td)
  }

  protected override def afterEach(td: TestData): Unit = {
    try {
      super.afterEach(td)
    }
    finally {
      testElapsed = System.currentTimeMillis - testStart
      logTime(s"Test ${td.name}", testElapsed, 3000)
      suiteElapsed += testElapsed
    }
  }

  protected override def beforeAll(): Unit = {
    if (sessionInitialized) {
      info(s"Parallelism: ${session.sparkContext.defaultParallelism.toString}")
    }
    suiteElapsed = 0
  }

  protected override def afterAll(): Unit = {
    logTime(s"Suite $this", suiteElapsed, 10000)
    if (sessionInitialized) {
      info("Shutting down spark session")
      session.stop()
    }
  }

  // Utilities

  def withoutLogging[T](e: => T): T = {
    // This should really keep the old level, but there is no sc.getLogLevel, so
    // take the cheap way out for now: just use "WARN", and do something proper
    // when/if needed
    sc.setLogLevel("OFF")
    try e finally sc.setLogLevel("WARN")
  }

  def interceptWithoutLogging[E <: Exception: ClassTag](e: => Any): Unit = {
    withoutLogging { intercept[E] { e }; () }
  }

  def assertSparkException[E <: Exception: ClassTag](stage: PipelineStage, data: DataFrame): Unit = {
    withoutLogging {
      intercept[E] {
        val transformer = stage match {
            case e: Estimator[_] => e.fit(data)
            case t: Transformer  => t
            case _ => sys.error(s"Unknown PipelineStage value: $stage")
          }
        // use .length to force the pipeline (.count might work, but maybe it's sometimes optimized)
        transformer.transform(data).foreach { r => r.length; () }
      }
      ()
    }
  }

  import session.implicits._

  def makeBasicDF(): DataFrame = {
    val df = Seq(
      (0, "guitars", "drums"),
      (1, "piano", "trumpet"),
      (2, "bass", "cymbals")).toDF("numbers","words", "more")
    df
  }

  def makeBasicNullableDF(): DataFrame = {
    val df = Seq(
      (0, 2.5, "guitars", "drums"),
      (1, Double.NaN, "piano", "trumpet"),
      (2, 8.9, "bass", null)).toDF("indices", "numbers","words", "more")
    df
  }

  def verifyResult(expected: DataFrame, result: DataFrame): Boolean = {
    assert(expected.count == result.count)
    assert(expected.schema.length == result.schema.length)
    (expected.columns zip result.columns).forall{ case (x,y) => x == y }
  }

  def time[R](block: => R): R = {
    val t0     = System.nanoTime()
    val result = block
    val t1     = System.nanoTime()
    println(s"Elapsed time: ${(t1 - t0) / 1e9} sec")
    result
  }

  private def logTime(name: String, time: Long, threshold: Long) = {
    val msg = s"$name took ${time / 1000.0}s"
    if (time > threshold) {
      alert(msg)
    } else {
      info(msg)
    }
  }

}
