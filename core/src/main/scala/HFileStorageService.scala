package com.foursquare.twofishes

import com.foursquare.twofishes.util.GeometryUtils
import com.twitter.util.{Duration, FuturePool}
import java.io._
import java.net.URI
import java.nio.ByteBuffer
import java.util.Arrays
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.hbase.io.hfile.{BlockCache, CacheConfig, FoursquareCacheConfig, HFile, HFileScanner}
import org.apache.hadoop.hbase.util.Bytes._
import org.apache.thrift.{TBaseHelper, TDeserializer}
import org.apache.thrift.protocol.TCompactProtocol
import org.bson.types.ObjectId
import scala.collection.JavaConversions._
import scalaj.collection.Implicits._

class HFileStorageService(basepath: String, shouldPreload: Boolean) extends GeocodeStorageReadService {
  val nameMap = new NameIndexHFileInput(basepath, shouldPreload)
  val oidMap = new GeocodeRecordHFileInput(basepath, shouldPreload)
  val geomMap = new GeometryHFileInput(basepath, shouldPreload)
  val s2mapOpt = ReverseGeocodeHFileInput.readInput(basepath, shouldPreload)
  val slugFidMap = new SlugFidHFileInput(basepath, shouldPreload)

  // will only be hit if we get a reverse geocode query
  lazy val s2map = s2mapOpt.getOrElse(
    throw new Exception("s2/revgeo index not built, please build s2_index.hfile"))

  def getIdsByNamePrefix(name: String): Seq[ObjectId] = {
    nameMap.getPrefix(name)
  }

  def getIdsByName(name: String): Seq[ObjectId] = {
    nameMap.get(name)
  }

  def getByName(name: String): Seq[GeocodeServingFeature] = {
    getByObjectIds(nameMap.get(name)).map(_._2).toSeq
  }

  def getByObjectIds(oids: Seq[ObjectId]): Map[ObjectId, GeocodeServingFeature] = {
    oidMap.getByObjectIds(oids)
  }
 
  def getBySlugOrFeatureIds(ids: Seq[String]) = {
    val oidMap = (for {
      id <- ids
      oid <- slugFidMap.get(id)
    } yield { (oid, id) }).toMap

    getByObjectIds(oidMap.keys.toList).map({
      case (k, v) => (oidMap(k), v)
    })
  }

  def getByS2CellId(id: Long): Seq[CellGeometry] = {
    s2map.get(id)
  }

  def getPolygonByObjectId(id: ObjectId): Option[Array[Byte]] = {
    geomMap.get(id)
  }

  def getMinS2Level: Int = s2map.minS2Level
  def getMaxS2Level: Int = s2map.maxS2Level
  override  def getLevelMod: Int = s2map.levelMod
}

abstract class HFileInput(basepath: String, filename: String, shouldPreload: Boolean) {
  val conf = new Configuration()
  val fs = new LocalFileSystem()
  fs.initialize(URI.create("file:///"), conf)

  val path = new Path(new File(basepath, filename).getAbsolutePath())
  val cache = new FoursquareCacheConfig()

  val reader = HFile.createReader(path.getFileSystem(conf), path, cache)

  val fileInfo = reader.loadFileInfo().asScala

  // prefetch the hfile
  if (shouldPreload) {
    val (rv, duration) = Duration.inMilliseconds({    
      val scanner = reader.getScanner(true, false) // Seek, caching.
      scanner.seekTo()
      while(scanner.next()) {}
    })

    println("took %s seconds to read %s".format(duration.inSeconds, filename))
  }

  def lookup(key: ByteBuffer): Option[ByteBuffer] = {
    val scanner: HFileScanner = reader.getScanner(true, true)
    if (scanner.reseekTo(key.array, key.position, key.remaining) == 0) {
      Some(scanner.getValue.duplicate())
    } else {
      None
    }
  }

  import scala.collection.mutable.ListBuffer

  def lookupPrefix(key: String, minPrefixRatio: Double = 0.5): Seq[Array[Byte]] = {
    val scanner: HFileScanner = reader.getScanner(true, true)
    scanner.seekTo(key.getBytes())
    if (!new String(scanner.getKeyValue().getKey()).startsWith(key)) {
      scanner.next()
    }


    val ret: ListBuffer[Array[Byte]] = new ListBuffer()

    // I hate to encode this logic here, but I don't really want to thread it
    // all the way through the storage logic.
    while (new String(scanner.getKeyValue().getKey()).startsWith(key)) {
      if ((key.size >= 3) ||
          (key.size*1.0 / new String(scanner.getKeyValue().getKey()).size) >= minPrefixRatio) {
        ret.append(scanner.getKeyValue().getValue())
      }
      scanner.next()
    }

    ret
  }

  def deserializeBytes[T <: org.apache.thrift.TBase[_ <: org.apache.thrift.TBase[_ <: AnyRef, _ <: org.apache.thrift.TFieldIdEnum], _ <: org.apache.thrift.TFieldIdEnum]](s: T, bytes: Array[Byte]): T = {
    val deserializer = new TDeserializer(new TCompactProtocol.Factory());
    deserializer.deserialize(s, bytes);
    s
  }
}

trait ObjectIdReader {
  def decodeObjectIds(bytes: Array[Byte]): Seq[ObjectId] = {
    0.until(bytes.length / 12).map(i => {
      new ObjectId(Arrays.copyOfRange(bytes, i * 12, (i + 1) * 12))
    })
  }
}

class NameIndexHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "name_index.hfile", shouldPreload) with ObjectIdReader {
  val prefixMapOpt = PrefixIndexHFileInput.readInput(basepath, shouldPreload)

  def get(name: String): List[ObjectId] = {
    val buf = ByteBuffer.wrap(name.getBytes())
    lookup(buf).toList.flatMap(b => {
      val bytes = TBaseHelper.byteBufferToByteArray(b)
      decodeObjectIds(bytes)
    })
  }

  def getPrefix(name: String): Seq[ObjectId] = {
    prefixMapOpt match {
      case Some(prefixMap) if (name.length <= prefixMap.maxPrefixLength) => {
        prefixMap.get(name)
      }
      case _  => {
        lookupPrefix(name).flatMap(bytes => {
          decodeObjectIds(bytes)
        })
      }
    }
  }
}

object PrefixIndexHFileInput {
  def readInput(basepath: String, shouldPreload: Boolean) = {
    if (new File(basepath, "prefix_index.hfile").exists()) {
      Some(new PrefixIndexHFileInput(basepath, shouldPreload))
    } else {
      None
    }
  } 
}

class PrefixIndexHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "prefix_index.hfile", shouldPreload) with ObjectIdReader {
  val maxPrefixLength = 5 // TODO: pull from hfile metadata  

  def get(name: String): List[ObjectId] = {
    val buf = ByteBuffer.wrap(name.getBytes())
    lookup(buf).toList.flatMap(b => {
      val bytes = TBaseHelper.byteBufferToByteArray(b)
      decodeObjectIds(bytes)
    })
  }
}

object ReverseGeocodeHFileInput {
  def readInput(basepath: String, shouldPreload: Boolean) = {
    if (new File(basepath, "s2_index.hfile").exists()) {
      Some(new ReverseGeocodeHFileInput(basepath, shouldPreload))
    } else {
      None
    }
  } 
}

class ReverseGeocodeHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "s2_index.hfile", shouldPreload) with ObjectIdReader {
  def fromByteArray(bytes: Array[Byte]) = {
    ByteBuffer.wrap(bytes).getInt()
  } 

  lazy val minS2Level = fromByteArray(fileInfo.getOrElse(
    "minS2Level".getBytes("UTF-8"),
    throw new Exception("missing minS2Level")))

  lazy val maxS2Level = fromByteArray(fileInfo.getOrElse(
    "maxS2Level".getBytes("UTF-8"),
    throw new Exception("missing maxS2Level")))

  lazy val levelMod = fromByteArray(fileInfo.getOrElse(
    "levelMod".getBytes("UTF-8"),
    throw new Exception("missing levelMod")))

  def get(cellid: Long): List[CellGeometry] = {
    val buf = ByteBuffer.wrap(GeometryUtils.getBytes(cellid))
    lookup(buf).toList.flatMap(b => {
      val bytes = TBaseHelper.byteBufferToByteArray(b)
      val geometries = new CellGeometries()
      deserializeBytes(geometries, bytes)
      geometries.cells
    })
  }
}

class GeometryHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "geometry.hfile", shouldPreload) with ObjectIdReader { 
  def get(oid: ObjectId): Option[Array[Byte]] = {
    val buf = ByteBuffer.wrap(oid.toByteArray())
    lookup(buf).map(b => {
      TBaseHelper.byteBufferToByteArray(b)
    })
  }
}

class SlugFidHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "id-mapping.hfile", shouldPreload) with ObjectIdReader { 
  def get(s: String): Option[ObjectId] = {
    val buf = ByteBuffer.wrap(s.getBytes("UTF-8"))
    lookup(buf).flatMap(b => {
      val bytes = TBaseHelper.byteBufferToByteArray(b)
      decodeObjectIds(bytes).headOption
    })
  }
}

class GeocodeRecordHFileInput(basepath: String, shouldPreload: Boolean)
    extends HFileInput(basepath, "features.hfile", shouldPreload) with ObjectIdReader { 

  def decodeFeature(b: ByteBuffer) = {
    val bytes = TBaseHelper.byteBufferToByteArray(b)
    deserializeBytes(new GeocodeServingFeature(), bytes)
  }

  def getByObjectIds(oids: Seq[ObjectId]): Map[ObjectId, GeocodeServingFeature] = {
    val comp = new ByteArrayComparator()
    val sortedOids = oids.map(oid => (oid.toByteArray(), oid)).toList.sortWith((a, b) => {
      comp.compare(a._1, b._1) < 0
    })

    val scanner: HFileScanner = reader.getScanner(true, true)
    def find(b: Array[Byte]) = {
      val key = ByteBuffer.wrap(b)
      if (scanner.seekTo(key.array, key.position, key.remaining) == 0) {
        Some(scanner.getValue.duplicate())
      } else {
        None
      }
    }

    sortedOids.flatMap({case (oidBytes, oid) => find(oidBytes).map(f => (oid, decodeFeature(f)))}).toMap
  }

  def get(oid: ObjectId): Option[GeocodeServingFeature] = {
    val buf = ByteBuffer.wrap(oid.toByteArray())
    lookup(buf).map(decodeFeature)
  }
}
