// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import java.nio.file.Files
import com.microsoft.ml.spark.FileUtilities.File
import scala.collection.JavaConversions._

class DownloaderSuite extends TestBase {

  val saveDir = Files.createTempDirectory("Models-").toFile
  val d = new ModelDownloader(session, saveDir.toURI)

  test("A downloader should be able to download a model", TestBase.Extended) {
    val m = d.remoteModels.filter(_.name == "CNN").next()
    val schema = d.downloadModel(m)
    println(schema)
    assert(m.size == new File(schema.uri).length())
    assert(d.localModels.toList.length == 1)
  }

  test("A downloader should be able to get all Models " +
    "and maybeDownload should be fast if models are downloaded", TestBase.Extended) {

    d.downloadModels()
    val modTimes = d.localModels.map(s =>
      new File(s.uri).lastModified())

    d.downloadModels()
    val modTimes2 = d.localModels.map(s =>
      new File(s.uri).lastModified())

    // No modification on second call because models are cached
    assert(modTimes.toList === modTimes2.toList)

    // the downloader's local models will reflect the change
    assert(d.localModels.toList.length == d.remoteModels.toList.length)

    // there will be a metadata file for every model
    assert(saveDir.list().count(_.endsWith(".meta")) == d.localModels.toList.length)
  }

  override def afterAll(): Unit = {
    FileUtilities.delTree(saveDir)
    super.afterAll()
  }

}
