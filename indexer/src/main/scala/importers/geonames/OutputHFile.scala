package com.foursquare.twofish

import org.apache.hadoop.hbase.io.hfile.{Compression, HFile}
import org.apache.hadoop.hbase.io.hfile.HFileWriterV2
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.fs.LocalFileSystem
import org.apache.hadoop.fs.Path

import com.novus.salat._
import com.novus.salat.global._
import com.novus.salat.annotations._
import com.novus.salat.dao._
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection

import org.apache.hadoop.conf.Configuration 
import org.apache.hadoop.hbase.io.hfile.CacheConfig

import java.net.URI

import org.apache.hadoop.fs.permission.FsPermission

import java.nio.ByteBuffer
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.hbase.io.hfile.{CacheConfig, HFile, HFileScanner}


abstract class HFileInput(hfile: String) {
  val conf = new Configuration()
  val fs = new LocalFileSystem()
  fs.initialize(URI.create("file:///"), conf)
  val path = new Path(hfile)
  val cacheConfig = new CacheConfig(conf)
  val reader = HFile.createReader(fs, path, cacheConfig)
  reader.loadFileInfo()

  def readSome(limit: Int) {
    val scanner: HFileScanner = reader.getScanner(true, true)
    var count = 0
    scanner.seekTo()

    0.to(limit).foreach(i => {
      println(scanner.getKeyString())
      val b = scanner.getValue()
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      println(new ObjectId(bytes))
      scanner.next()
    })

  }

  def lookup(key: ByteBuffer): Option[ByteBuffer] = {
    val scanner: HFileScanner = reader.getScanner(true, true)
    if (scanner.reseekTo(key.array, key.position, key.remaining) == 0) {
      Some(scanner.getValue.duplicate())
    } else {
      None
    }
  }
}

class FidIndexHFileInput extends HFileInput("/tmp/fid_index.hfile") {
  def get(fid: String): Option[ObjectId] = {

    val buf = ByteBuffer.wrap(fid.getBytes())

    lookup(buf).map(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      new ObjectId(bytes)
    })
  }
}

object OutputHFile {
  val blockSizeKey = "hbase.mapreduce.hfileoutputformat.blocksize"
  val compressionKey = "hfile.compression"

  val blockSize = HFile.DEFAULT_BLOCKSIZE
  val compressionAlgo = Compression.Algorithm.NONE.getName

  // val fs = new RawLocalFileSystem() 
  // val path = new Path("/tmp/fid_index.hfile")
  // val writer = new HFile.Writer(fs, path, blockSize, compressionAlgo, null)
  // var fidCount = 0
  // val fidSize = FidIndexDAO.collection.count
  // val fidCursor = FidIndexDAO.find(MongoDBObject())
  // fidCursor.foreach(f => {
  //   writer.append(f.fid.getBytes(), f.oid.toByteArray())
  //   fidCount += 1
  //   if (fidCount % 1000 == 0) {
  //     println("processed %d of %d fids".format(fidCount, fidSize))
  //   }
  // })
  // writer.close()

  val conf = new Configuration();
  val cconf = new CacheConfig(conf);

  def writeCollection[T <: AnyRef, K <: Any](
    fpath: String,
    callback: (T) => (Array[Byte], Array[Byte]),
    dao: SalatDAO[T, K]
  ) {
    val fs = new LocalFileSystem() 
    val path = new Path(fpath)
    fs.initialize(URI.create("file:///"), conf)
    val writer = new HFileWriterV2(conf, cconf, fs, path, blockSize, compressionAlgo, null)
    var fidCount = 0
    val fidSize = dao.collection.count
    val fidCursor = dao.find(MongoDBObject())
      .sort(orderBy = MongoDBObject("_id" -> 1)) // sort by _id desc
    fidCursor.foreach(f => {
      val (k, v) = callback(f)
      writer.append(k, v)
      fidCount += 1
      if (fidCount % 10 == 0) {
        println("processed %d of %d %s".format(fidCount, fidSize, path))
      }
    })
    writer.close()
  }

  def main(args: Array[String]) {
    writeCollection("/tmp/fid_index.hfile",
      (f: FidIndex) => (f.fid.getBytes(), f.oid.toByteArray()),
      FidIndexDAO)

    writeCollection("/tmp/name_index.hfile",
      (n: NameIndex) => (n.name.getBytes(), n.fids.mkString(",").getBytes()),
      NameIndexDAO)
  }
}