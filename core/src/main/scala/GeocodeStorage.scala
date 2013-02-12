// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes

import com.vividsolutions.jts.io.WKBReader
import org.apache.thrift.{TDeserializer, TSerializer}
import org.apache.thrift.protocol.TCompactProtocol
import org.bson.types.ObjectId
import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap

object Implicits {
  implicit def fidToString(fid: StoredFeatureId): String = fid.toString
  implicit def fidListToString(fids: List[StoredFeatureId]): List[String] = fids.map(_.toString)
}

class SlugEntryMap extends HashMap[String, SlugEntry]

case class SlugEntry(
  id: String,
  score: Int,
  var deprecated: Boolean = false,
  permanent: Boolean = false
) {
  override def toString(): String = {
    "%s\t%s\t%s".format(id, score, deprecated)
  }
}

case class DisplayName(
  lang: String,
  name: String,
  flags: Int,
  _id: ObjectId = new ObjectId()
)

case class StoredFeatureId(
  namespace: String,
  id: String) {
  override def toString = "%s:%s".format(namespace, id)
}

case class Point(lat: Double, lng: Double)

case class BoundingBox(
  ne: Point,
  sw: Point
)

case class GeocodeRecord(
  _id: ObjectId = new ObjectId,
  ids: List[String],
  names: List[String],
  cc: String,
  _woeType: Int,
  lat: Double,
  lng: Double,
  displayNames: List[DisplayName],
  parents: List[String],
  population: Option[Int],
  boost: Option[Int] = None,
  boundingbox: Option[BoundingBox] = None,
  canGeocode: Boolean = true,
  slug: Option[String] = None,
  polygon: Option[Array[Byte]] = None,
  hasPoly: Option[Boolean] = None,
  var attributes: Option[Array[Byte]] = None
) extends Ordered[GeocodeRecord] {
  val factory = new TCompactProtocol.Factory()
  val serializer = new TSerializer(factory)
  val deserializer = new TDeserializer(factory)

  def setAttributes(attr: Option[GeocodeFeatureAttributes]) {
    attributes = attr.map(a => serializer.serialize(a))
  }

  def getAttributes(): Option[GeocodeFeatureAttributes] = {
    attributes.map(bytes => {
      val attr = new GeocodeFeatureAttributes()
      deserializer.deserialize(attr, bytes)
      attr
    })
  }

  def featureIds = ids.map(id => {
    val parts = id.split(":")
    StoredFeatureId(parts(0), parts(1))
  })

  lazy val woeType = YahooWoeType.findByValue(_woeType)
  
  def compare(that: GeocodeRecord): Int = {
    YahooWoeTypes.getOrdering(this.woeType) - YahooWoeTypes.getOrdering(that.woeType)
  }

  def toGeocodeServingFeature(): GeocodeServingFeature = {
    // geom
    val geometry = new FeatureGeometry(
      new GeocodePoint(lat, lng))

    boundingbox.foreach(bounds => {
      val currentBounds = (bounds.ne.lat, bounds.ne.lng, bounds.sw.lat, bounds.sw.lng)

      // This breaks at 180, I get that, to fix.
      val finalBounds = (
        List(bounds.ne.lat, bounds.sw.lat).max,
        List(bounds.ne.lng, bounds.sw.lng).max,
        List(bounds.ne.lat, bounds.sw.lat).min,
        List(bounds.ne.lng, bounds.sw.lng).min
      )

      if (finalBounds != currentBounds) {
        println("incorrect bounds %s -> %s".format(currentBounds, finalBounds))
      }

      geometry.setBounds(new GeocodeBoundingBox(
        new GeocodePoint(finalBounds._1, finalBounds._2),
        new GeocodePoint(finalBounds._3, finalBounds._4)
      ))  
    })

    polygon.foreach(poly => {
      geometry.setWkbGeometry(poly)

      if (boundingbox.isEmpty) {
        val wkbReader = new WKBReader()
        val g = wkbReader.read(poly)

        val envelope = g.getEnvelopeInternal()

        geometry.setBounds(new GeocodeBoundingBox(
          new GeocodePoint(envelope.getMaxY(), envelope.getMaxX()),
          new GeocodePoint(envelope.getMinY(), envelope.getMinX())
        ))
      }
    })

    val feature = new GeocodeFeature(
      cc, geometry
    )

    feature.setWoeType(this.woeType)

    feature.setIds(featureIds.filterNot(_.namespace == "gadminid").map(i => {
      new FeatureId(i.namespace, i.id)
    }))

    feature.ids.headOption.foreach(id => feature.setId("%s:%s".format(id.source, id.id)))

    val filteredNames: List[DisplayName] = displayNames.filterNot(n => List("post", "link").contains(n.lang))
    var hackedNames: List[DisplayName] = Nil

    // HACK(blackmad)
    if (this.woeType == YahooWoeType.ADMIN1 && cc == "JP") {
      hackedNames ++= 
        filteredNames.filter(n => n.lang == "en" || n.lang == "" || n.lang == "alias")
          .map(n => DisplayName(n.lang, n.name + " Prefecture", FeatureNameFlags.ALIAS.getValue))
    }

    if (this.woeType == YahooWoeType.TOWN && cc == "TW") {
      hackedNames ++= 
        filteredNames.filter(n => n.lang == "en" || n.lang == "" || n.lang == "alias")
          .map(n => DisplayName(n.lang, n.name + " County", FeatureNameFlags.ALIAS.getValue))
    }

    val allNames = filteredNames ++ hackedNames

    val nameCandidates = allNames.map(name => {
      var flags: List[FeatureNameFlags] = Nil
      if (name.lang == "abbr") {
        flags ::= FeatureNameFlags.ABBREVIATION
      }

      FeatureNameFlags.values.foreach(v => {
        if ((v.getValue() & name.flags) > 0) {
          flags ::= v
        }
      })

      val fname = new FeatureName(name.name, name.lang)
      if (flags.nonEmpty) {
        fname.setFlags(flags)
      }
      fname
    })

    feature.setNames(nameCandidates
      .groupBy(n => "%s%s%s".format(n.lang, n.name, n.flags))
      .flatMap({case (k,v) => v.headOption})
      .toList
    )

    slug.foreach(s => feature.setSlug(s))

    def makeParents(ids: List[String], relationType: GeocodeRelationType) = {
      ids.map(id => {
        val relation = new GeocodeRelation()
        relation.setRelationType(GeocodeRelationType.PARENT)
        relation.setRelatedId(id)
        relation
      })
    }

    val scoring = new ScoringFeatures()
    boost.foreach(b => scoring.setBoost(b))
    population.foreach(p => scoring.setPopulation(p))
    scoring.setParents(parents)

    getAttributes().foreach(a => feature.setAttributes(a))

    if (!canGeocode) {
      scoring.setCanGeocode(false)
    }
    
    val servingFeature = new GeocodeServingFeature()
    servingFeature.setId(_id.toString)
    servingFeature.setScoringFeatures(scoring)
    servingFeature.setFeature(feature)

    servingFeature
  }

  def isCountry = woeType == YahooWoeType.COUNTRY
  def isPostalCode = woeType == YahooWoeType.POSTAL_CODE
}

trait GeocodeStorageReadService {
  def getIdsByName(name: String): Seq[ObjectId]
  def getIdsByNamePrefix(name: String): Seq[ObjectId]
  def getByName(name: String): Seq[GeocodeServingFeature]
  def getByObjectIds(ids: Seq[ObjectId]): Map[ObjectId, GeocodeServingFeature]

  def getBySlugOrFeatureIds(ids: Seq[String]): Map[String, GeocodeServingFeature]

  def getMinS2Level: Int
  def getMaxS2Level: Int
  def getLevelMod: Int
  def getByS2CellId(id: Long): Seq[CellGeometry]
  def getPolygonByObjectId(id: ObjectId): Option[Array[Byte]]
}
