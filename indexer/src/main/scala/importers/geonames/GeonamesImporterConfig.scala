// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes.importers.geonames

class GeonamesImporterConfig(args: Array[String]) {
  // Buildings are places like the eiffel tower, but also schools and federal offices.
  var shouldParseBuildings = false

  // If set to true, the code expects data/downloaded/allCountries.txt and data/downloaded/zip/allCountries.txt
  // If set to false, the code expects data/downloaded/<parseCountry>.txt and data/downloaded/zip/<parseCountry>.txt
  var parseWorld = false
  var parseCountry = "US"

  // Expects data/downloaded/alternateNames.txt
  // This is an important file because it contains translated names, abbreviated names, and preferred names
  // for each feature. Without it, we can't generate pretty strings for display
  var importAlternateNames = true
  var importPostalCodes = true

  // Geonames doesn't have bounding boxes, only points. This is a precomputed mapping of geonameids to yahoo
  // woeids to flickr bounding boxes. Precomputed because I could't get the geojson java libraries to work.
  var importBoundingBoxes = true
  var boundingBoxDirectory = "./data/computed/bboxes/"

  var buildMissingSlugs = false

  var hfileBasePath: String = null

  var outputPrefixIndex: Boolean = true
  var outputRevgeo: Boolean = false

  private val config = this

  val parser = 
    new scopt.OptionParser("twofishes", "0.12") {
      booleanOpt("parse_world", "parse the whole world, or one country",
        { v: Boolean => config.parseWorld = v } )
      opt("parse_country", "country to parse, two letter iso code",
        { v: String => config.parseCountry = v } )
      booleanOpt("parse_postal_codes", "parse postal codes",
        { v: Boolean => config.importPostalCodes = v } )
      opt("hfile_basepath", "directory to output hfiles to",
        { v: String => config.hfileBasePath = v} )
      booleanOpt("parse_alternate_names", "parse alternate names",
        { v: Boolean => config.importAlternateNames = v } )
      booleanOpt("output_prefix_index", "wheter or not to output autocomplete acceleration index",
        { v: Boolean => config.outputPrefixIndex = v} )
      booleanOpt("build_missing_slugs", "build pretty hopefully stable slugs per feature",
        { v: Boolean => config.buildMissingSlugs = v } )
      booleanOpt("output_revgeo_index", "wheter or not to output s2 revgeo index",
        { v: Boolean => config.outputRevgeo = v} )
    }

  if (!parser.parse(args)) {
    // arguments are bad, usage message will have been displayed
    System.exit(1)
  }

  if (hfileBasePath == null) {
    println("must specify --hfile_basepath")
    System.exit(1)
  }
}
