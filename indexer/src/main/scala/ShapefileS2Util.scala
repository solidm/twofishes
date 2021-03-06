// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.geo.shapes

import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory, LinearRing}
import org.opengis.feature.`type`.{GeometryType, AttributeDescriptor, AttributeType}
import com.google.common.geometry.{S2Cell, S2CellId, S2LatLng}
import scala.collection.mutable
import scalaj.collection.Imports._

object ShapefileS2Util {
  def clipGeometryToCell(
    geom: Geometry,
    cellid: S2CellId): (Geometry, Boolean) = {
    val cell= new S2Cell(cellid)

   val geomFactory = new GeometryFactory()
    val vertexIndexes = List(0, 1, 2, 3, 0)
    val coords = vertexIndexes.map(vertexIndex => {
      val point = cell.getVertex(vertexIndex)
      val latlng = new S2LatLng(point)
      new Coordinate(latlng.lngDegrees(), latlng.latDegrees())
    }).toArray

    val ring = geomFactory.createLinearRing(coords)
    val holes: Array[LinearRing] = null // use LinearRing[] to represent holes
    val cellPolygon = geomFactory.createPolygon(ring, holes)

    (cellPolygon.intersection(geom), cellPolygon.contains(geom))
  }
}