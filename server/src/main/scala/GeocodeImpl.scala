//  Copyright 2012 Foursquare Labs Inc. All Rights Reserved
package com.foursquare.twofishes

import com.foursquare.twofishes.Implicits._
import com.foursquare.twofishes.util.{GeoTools, GeometryUtils, NameNormalizer, NameUtils, TwofishesLogger}
import com.foursquare.twofishes.util.Lists.Implicits._
import com.foursquare.twofishes.util.NameUtils.BestNameMatch
import com.twitter.ostrich.stats.Stats
import com.twitter.util.Duration
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory}
import com.vividsolutions.jts.io.{WKBReader, WKTWriter}
import com.vividsolutions.jts.util.GeometricShapeFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import org.bson.types.ObjectId
import scala.collection.JavaConversions._
import scala.collection.mutable.{HashMap, ListBuffer}
import scalaj.collection.Implicits._

// TODO
// 

// TODO
// --make autocomplete faster
// --save name-hit in the index
// --start treating Parse like an object, stop using headOption
// --test autocomplete more
// --better debugging
// --add more components if we have ambiguous names

// Represents a match from a run of tokens to one particular feature
case class FeatureMatch(
  tokenStart: Int,
  tokenEnd: Int,
  phrase: String,
  fmatch: GeocodeServingFeature
)

// Sort a list of features, smallest to biggest
object GeocodeServingFeatureOrdering extends Ordering[GeocodeServingFeature] {
  def compare(a: GeocodeServingFeature, b: GeocodeServingFeature) = {
    YahooWoeTypes.getOrdering(a.feature.woeType) - YahooWoeTypes.getOrdering(b.feature.woeType)
  }
}

// Sort a list of feature matches, smallest to biggest
object FeatureMatchOrdering extends Ordering[FeatureMatch] {
  def compare(a: FeatureMatch, b: FeatureMatch) = {
    GeocodeServingFeatureOrdering.compare(a.fmatch, b.fmatch)
  }
}

// I should really do this with shadow types :-(
case class SortedParse(
  fmatches: Seq[FeatureMatch]
)

trait MaybeSorted
trait Sorted extends MaybeSorted
trait Unsorted extends MaybeSorted

// Parse = one particular interpretation of the query
case class Parse[T <: MaybeSorted](
  fmatches: Seq[FeatureMatch],
  scoringFeatures: InterpretationScoringFeatures = new InterpretationScoringFeatures()
) extends Seq[FeatureMatch] {
  def apply(i: Int) = fmatches(i)
  def iterator = fmatches.iterator
  def length = fmatches.length

  def getSorted: Parse[Sorted] =
    Parse[Sorted](fmatches.sorted(FeatureMatchOrdering), scoringFeatures)

  def addFeature(f: FeatureMatch) = Parse[Unsorted](fmatches ++ List(f))
}

trait GeocoderImplTypes {
  val NullParse = Parse[Sorted](Nil)
  // ParseSeq = multiple interpretations
  type ParseSeq = Seq[Parse[Unsorted]]
  type SortedParseSeq = Seq[Parse[Sorted]]

  // ParseCache = a table to save our previous parses from the left-most
  // side of the string.
  type ParseCache = ConcurrentHashMap[Int, SortedParseSeq]
}

class MemoryLogger(req: GeocodeRequest) extends TwofishesLogger {
  val startTime = new Date()

  def timeSinceStart = {
    new Date().getTime() - startTime.getTime()
  }

  val lines: ListBuffer[String] = new ListBuffer()

  def ifDebug(s: => String, level: Int = 0) {
    if (level >= 0 && req.debug >= level) {
      lines.append("%d: %s".format(timeSinceStart, s))
    }
  }

  def getLines: List[String] = lines.toList

  def toOutput(): String = lines.mkString("<br>\n");
}

class GeocoderImpl(store: GeocodeStorageReadService, req: GeocodeRequest) extends GeocoderImplTypes {
  val logger = new MemoryLogger(req)

  /*
    The basic algorithm works like this
    - roughly a depth-first recursive descent parser
    - for every set of tokens from size 1->n, attempt to find a feature with a matching name in our datastore
      (at the first level, for "rego park ny", we'd try rego, rego park, rego park ny)
    - for every feature found matching one of our token sets, recursively try to geocode the remaining tokens
    - return early from a branch of our parse tree if the parse becomes invalid/inconsistent, that is,
      if the feature found does not occur in the parents of the smaller feature found 
      (we would about the parse of "los angeles, new york, united states" when we'd consumed "los angeles"
       its parents were CA & US, and then consumed "new york" because NY is not in the parents of LA)

    NOTE: what I just said was a bit of a lie, we dive all the way down to the left-most end of the string
    first and then come back up to save our work. We do this so that we can save our work in the cache for future
    parses. If we take two different parse trees to reach the same set of tokens,
    say [United States] in [New York United States], we want to save that we've already explored all the possibilities
    for United States.
    - save our work in a map[int -> parses], where the int is the total number of tokens those parses consume.
       we do this because:
       if two separate parses made it to the same point in the input, we don't need to redo the work
         (contrived example: Laurel Mt United States, can both be parsed as "Laurel" in "Mt" (Montana), 
          and "Laurel Mt" (Mountain), both consistent & valid parses. One of those parses would have already
         completely explored "united states" before the second one gets to it)
    - we return the entire cache, which is a little silly. The caller knows to look for the cache for the 
      largest key (the longest parse), and to take all the tokens before that and make the "what" (the
      non-geocoded tokens) in the final interpretation.
   */

  // for rego park ny us
  // first try us
  // then ny us (try "ny us" and ny -> "us"
  // then park ny us ()

   def generateParses(tokens: List[String]): ParseCache = {
    val cache = new ParseCache
    cache(0) = List(NullParse)
 
    (tokens.size - 1).to(0, -1).foreach(offset => {
      val subTokens = tokens.drop(offset)
      val validParses = generateParsesHelper(subTokens, offset, cache)
      val cacheKey = subTokens.size
      if (req.debug > 1) {
        logger.ifDebug("setting %d to %s".format(cacheKey, validParses))
      }
      cache(cacheKey) = validParses
    })
    cache
  }

  def generateParsesHelper(tokens: List[String], offset: Int, cache: ParseCache): SortedParseSeq = {
    1.to(tokens.size).flatMap(i => {
      val searchStr = tokens.take(i).mkString(" ")
      val featureMatches = logDuration("get-by-name", "get-by-name for %s".format(searchStr)) {
        store.getByName(searchStr).map((f: GeocodeServingFeature) => 
          FeatureMatch(offset, offset + i, searchStr, f)
        )
      }
      val subParses = cache(tokens.size - i)
      (for {
        f <- featureMatches
        p <- subParses
        val _ = logger.ifDebug("looking at %s".format(f), 3)
        val _ = logger.ifDebug("sub_parse: %s".format(p), 3)
      } yield {
        val parse = p.addFeature(f)
        val sortedParse = parse.getSorted
        if (isValidParse(sortedParse)) {
          logger.ifDebug("VALID", 4)
          logger.ifDebug("sorted " + sortedParse, 4)
          Some(sortedParse)
        } else {
          logger.ifDebug("INVALID", 4)
          None
        }
      }).flatten
    })
  }

  def isValidParse(parse: Parse[Sorted]): Boolean = {
    if (isValidParseHelper(parse)) {
      true
    } else {
      /*
       * zipcode hack
       * zipcodes don't belong to the political hierarchy, so they don't
       * have the parents you'd expect. Also, people call zipcodes a lot
       * of things other than the official name. As a result, we're going
       * to accept a parse that includes a zipcode if it's within 200km
       * of the next smallest feature
       */
      val sorted_parse = parse.getSorted
      (for {
        first <- sorted_parse.fmatches.lift(0)
        second <- sorted_parse.fmatches.lift(1)
      } yield {
        first.fmatch.feature.woeType == YahooWoeType.POSTAL_CODE &&
        isValidParseHelper(Parse[Sorted](sorted_parse.fmatches.drop(1))) &&
        GeoTools.getDistance(
          second.fmatch.feature.geometry.center.lat,
          second.fmatch.feature.geometry.center.lng,
          first.fmatch.feature.geometry.center.lat,
          first.fmatch.feature.geometry.center.lng) < 200000
      }).getOrElse(false)
    }
  }

  def isValidParseHelper(parse: Parse[Sorted]): Boolean = {
    if (parse.size <= 1) {
      true
    } else {
      val most_specific = parse(0)
      //logger.ifDebug("most specific: " + most_specific)
      //logger.ifDebug("most specific: parents" + most_specific.fmatch.scoringFeatures.parents)
      val rest = parse.drop(1)
      rest.forall(f => {
        //logger.ifDebug("checking if %s in parents".format(f.fmatch.id))
        f.fmatch.id == most_specific.fmatch.id ||
        most_specific.fmatch.scoringFeatures.parents.asScala.has(f.fmatch.id)
      })
    }
  }

  /**** AUTOCOMPLETION LOGIC ****/
  /*
   In an effort to make autocomplete much faster, we throw out a number of the 
   features and hacks of the primary matching logic. We assume that the query
   is being entered right-to-left, smallest-to-biggest. We also skip out on most
   of the zip-code hacks. Since zip-codes aren't really in the political hierarchy,
   they don't have all the parents one might expect them to. In the full scorer, we
   need to hydrate the full features for our name hits so that we can check the
   feature type and compare the distances as a hack for zip-code containment. 

   In this scorer, we assume the left-most strings that we match are matched to the
   smallest feature, and then we don't need to hydrate any future matches to determine
   validity, we only need to check whether the matched ids occur in the parents of
   that smallest feature

   This means that some logic is duplicated. sorry.

   */
  def buildValidParses(
      parses: ParseSeq,
      fids: Seq[ObjectId],
      offset: Int,
      i: Int,
      matchString: String): ParseSeq = {
    if (parses.size == 0) {
      logger.ifDebug("parses == 0, so accepting everything")
      store.getByObjectIds(fids).map({case(oid, feature) => {
        Parse[Unsorted](List(FeatureMatch(offset, offset + i,
          matchString, feature)))
      }}).toList
    } else {
      val validParsePairs: Seq[(Parse[Unsorted], ObjectId)] = parses.flatMap(parse => {
        if (req.debug > 0) {
          logger.ifDebug("checking %d fids against %s".format(fids.size, parse.map(_.fmatch.id)))
          logger.ifDebug("these are the fids of my parse: %s".format(fids))
        }
        fids.flatMap(fid => {
          if (req.debug > 0) {
            logger.ifDebug("checking if %s is an unused parent of %s".format(
              fid, parse.map(_.fmatch.id)))
          }

          val isValid = parse.exists(_.fmatch.scoringFeatures.parents.asScala.has(fid.toString)) &&
            !parse.exists(_.fmatch.id.toString == fid.toString)
          if (isValid) {
            if (req.debug > 0) {
              logger.ifDebug("HAD %s as a parent".format(fid))
            }
            Some((parse, fid))
          } else {
           // logger.ifDebug("wasn't")
           None
          }
        })
      })

      val fidsToFetch: Seq[ObjectId] = validParsePairs.map(_._2).toSet.toList
      val featuresMap = store.getByObjectIds(fidsToFetch)

      validParsePairs.flatMap({case(parse, fid) => {
        featuresMap.get(fid).map(feature => {
          parse.addFeature(FeatureMatch(offset, offset + i,
            matchString, feature))
        })
      }})
    }
  }

  def generateAutoParses(tokens: List[String], spaceAtEnd: Boolean): SortedParseSeq = {
    // The getSorted is redundant here because the isValid function for autocomplete
    // parses enforces smallest-to-largest ordering, but we can't prove that to the compiler
    generateAutoParsesHelper(tokens, 0, Nil, spaceAtEnd).map(_.getSorted)
  }

  def matchName(name: FeatureName, query: String, isEnd: Boolean): Boolean = {
    if (name.flags.contains(FeatureNameFlags.PREFERRED) ||
        name.flags.contains(FeatureNameFlags.ABBREVIATION) ||
        name.flags.contains(FeatureNameFlags.LOCAL_LANG) ||
        name.flags.contains(FeatureNameFlags.ALT_NAME)) { 
      val normalizedName = NameNormalizer.normalize(name.name)
      if (isEnd) {
        normalizedName.startsWith(query)
      } else {
        normalizedName == query
      }
    } else {
      false
    }
  }

  // Yet another huge hack because I don't know what name I hit
  def filterNonPrefExactAutocompleteMatch(ids: Seq[ObjectId], phrase: String): Seq[ObjectId] = {
    store.getByObjectIds(ids).filter(f => {
      f._2.feature.woeType == YahooWoeType.POSTAL_CODE ||
      {
        val nameMatch = bestNameWithMatch(f._2.feature, Some(req.lang), false, Some(phrase))
        nameMatch.exists(nm => 
          nm._1.flags.contains(FeatureNameFlags.PREFERRED) ||
          nm._1.flags.contains(FeatureNameFlags.ALT_NAME)
        )
      }
    }).toList.map(_._1)
  }

  def generateAutoParsesHelper(tokens: List[String], offset: Int, parses: ParseSeq, spaceAtEnd: Boolean): ParseSeq = {
    if (tokens.size == 0) {
      parses
    } else {
      val validParses: Seq[ParseSeq] = 1.to(tokens.size).map(i => {
        val query = tokens.take(i).mkString(" ")
        val isEnd = (i == tokens.size)

        val possibleParents = parses.flatMap(_.flatMap(_.fmatch.scoringFeatures.parents))
          .map(parent => new ObjectId(parent))

        val featureIds: Seq[ObjectId] = 
          if (parses.size == 0) {
            if (isEnd) {
              logger.ifDebug("looking at prefix: %s".format(query))
              if (spaceAtEnd) {
                List(
                  store.getIdsByNamePrefix(query + " "),
                  filterNonPrefExactAutocompleteMatch(store.getIdsByName(query), query)
                ).flatten
              } else {
                store.getIdsByNamePrefix(query)
              }
            } else {
              store.getIdsByName(query)
            }
          } else {
            store.getByObjectIds(possibleParents).filter(feature => {
              feature._2.feature.names.exists(n => matchName(n, query, isEnd))
            }).map(_._1).toList
          }

        if (req.debug > 0) {
          logger.ifDebug("%d-%d: looking at: %s (is end? %s)".format(offset, i, query, isEnd))
          logger.ifDebug("%d-%d: %d previous parses: %s".format(offset, i, parses.size,   parses))
          logger.ifDebug("%d-%d: examining %d featureIds against parse".format(offset, i, featureIds.size))
        }

        val nextParses: ParseSeq = buildValidParses(parses, featureIds, offset, i, query)

        if (nextParses.size == 0) {
          List(Parse[Unsorted](Nil))
        } else {
          generateAutoParsesHelper(tokens.drop(i), offset + i, nextParses, spaceAtEnd)
        }
      })
      validParses.flatten
    }
  }

  // Another delightful hack. We don't save a pointer to the specific name we matched
  // in our inverted index, instead, if we know which tokens matched this feature,
  // we look for the name that, when normalized, matches the query substring.
  // i.e. for [New York, NY, Nueva York], if we know that we matched "neuv", we
  // look for the names that started with that.
  var nameMatchMap =
    new scala.collection.mutable.HashMap[String, Option[BestNameMatch]]

  def bestNameWithMatch(
    f: GeocodeFeature,
    lang: Option[String],
    preferAbbrev: Boolean,
    matchedStringOpt: Option[String]
  ): Option[BestNameMatch] = {
    val hashKey = "%s:%s:%s:%s".format(f.ids, lang, preferAbbrev, matchedStringOpt)
    if (!nameMatchMap.contains(hashKey)) {
      nameMatchMap(hashKey) = NameUtils.bestName(f, lang, preferAbbrev, matchedStringOpt, req.debug, logger)
    }
    nameMatchMap(hashKey)
  }

  def parseHasDupeFeature(p: Parse[_]): Boolean = {
    p.headOption.exists(primaryFeature => {
      val rest = p.drop(1)
      rest.exists(_.fmatch.feature.ids == primaryFeature.fmatch.feature.ids)
    })
  }

  // Comparator for parses, we score by a number of different features
  // 
  class ParseOrdering extends Ordering[Parse[Sorted]] {
    var scoreMap = new scala.collection.mutable.HashMap[String, Int]

    // Higher is better
    def scoreParse(parse: Parse[Sorted]): Int = {
      parse.headOption.map(primaryFeatureMatch => {
        val primaryFeature = primaryFeatureMatch.fmatch
        val rest = parse.drop(1)
        var signal = primaryFeature.scoringFeatures.population

        def modifySignal(value: Int, debug: String) {
          if (req.debug > 0) {
            logger.ifDebug("%s: %s + %s = %s".format(
              debug, signal, value, signal + value))
          }
          signal += value
        }
      
        if (req.debug > 0) {
          logger.ifDebug("Scoring %s".format(printDebugParse(parse)))
        }

        // if we have a repeated feature, downweight this like crazy
        // so st petersburg, st petersburg works, but doesn't break new york, ny
        if (parseHasDupeFeature(parse)) {
          modifySignal(-100000000, "downweighting dupe-feature parse")
        }

        if (primaryFeature.feature.geometry.bounds != null) {
          modifySignal(1000, "promoting feature with bounds")
        }

        if (req.woeHint.asScala.has(primaryFeature.feature.woeType)) {
          modifySignal(50000000,
            "woe hint matches %d".format(primaryFeature.feature.woeType.getValue))
        }

        // prefer a more aggressive parse ... bleh
        // this prefers "mt laurel" over the town of "laurel" in "mt" (montana)
        modifySignal(-5000 * parse.length, "parse length boost")

        // Matching country hint is good
        if (Option(req.cc).exists(_ == primaryFeature.feature.cc)) {
          modifySignal(10000, "country code match")
        }

        def distancePenalty(ll: GeocodePoint) {
          val distance = if (primaryFeature.feature.geometry.bounds != null) {
            GeoTools.distanceFromPointToBounds(ll, primaryFeature.feature.geometry.bounds)
          } else {
            GeoTools.getDistance(ll.lat, ll.lng,
              primaryFeature.feature.geometry.center.lat,
              primaryFeature.feature.geometry.center.lng)
          }
          val distancePenalty = (distance.toInt / 100)
          if (distance < 5000) {
            modifySignal(200000, "5km distance BONUS for being %s meters away".format(distance))
          } else {
            modifySignal(-distancePenalty, "distance penalty for being %s meters away".format(distance)) 
          }
        }

        val llHint = Option(req.ll)
        val boundsHint = Option(req.bounds)
        if (boundsHint.isDefined) {
          boundsHint.foreach(bounds => {
            // if you're in the bounds and the bounds are some small enough size
            // you get a uniform boost
            val bbox = GeoTools.boundingBoxToS2Rect(bounds)
            // distance in meters of the hypotenuse
            // if it's smaller than looking at 1/4 of new york state, then
            // boost everything in it by a lot
            val bboxContainsCenter = 
              GeoTools.boundsContains(bounds, primaryFeature.feature.geometry.center)
            val bboxesIntersect = 
              Option(primaryFeature.feature.geometry.bounds).map(fBounds =>
                GeoTools.boundsIntersect(bounds, fBounds)).getOrElse(false)

            if (bbox.lo().getEarthDistance(bbox.hi()) < 200 * 1000 &&
              (bboxContainsCenter || bboxesIntersect)) {
              modifySignal(200000, "200km bbox intersection BONUS")
            } else {
              // fall back to basic distance-from-center logic
              distancePenalty(GeoTools.S2LatLngToPoint(bbox.getCenter))
            }
          })
        } else if (llHint.isDefined) {
          // Penalize far-away things
          llHint.foreach(distancePenalty)
        }

        // manual boost added at indexing time
        if (primaryFeature.scoringFeatures.boost != 0) {
          modifySignal(primaryFeature.scoringFeatures.boost, "manual boost")
        }

        // as a terrible tie break, things in the US > elsewhere
        // meant primarily for zipcodes
        if (primaryFeature.feature.cc == "US") {
          modifySignal(1, "US tie-break")
        }

        // no one likes counties
        if (primaryFeature.feature.cc == "US" && primaryFeature.feature.woeType == YahooWoeType.ADMIN2) {
          modifySignal(-30000, "no one likes counties in the US")
        }

        // In autocomplete mode, prefer "tighter" interpretations
        // That is, prefer "<b>Rego Park</b>, <b>N</b>Y" to 
        // <b>Rego Park</b>, NY, <b>N</b>aalagaaffeqatigiit
        //
        // getOrdering returns a smaller # for a smaller thing
        val parentTypes = rest.map(_.fmatch.feature.woeType).sortBy(YahooWoeTypes.getOrdering)
        if (parentTypes.nonEmpty) {
          if (parentTypes(0) == YahooWoeType.ADMIN2 && req.autocomplete) {
            modifySignal( -20, "downweight county matches a lot in autocomplete mode")
          } else {
            modifySignal( -1 * YahooWoeTypes.getOrdering(parentTypes(0)), "prefer smaller parent interpretation")
          }
        }

        modifySignal( -1 * YahooWoeTypes.getOrdering(primaryFeature.feature.woeType), "prefer smaller interpretation")

        logger.ifDebug("final score %s".format(signal))
        signal
      }).getOrElse(0)
    }

    def getScore(p: Parse[Sorted]): Int = {
      val scoreKey = p.map(_.fmatch.id).mkString(":")
      if (!scoreMap.contains(scoreKey)) {
        scoreMap(scoreKey) = scoreParse(p)
      }

      scoreMap.getOrElse(scoreKey, -1)
    }

    def compare(a: Parse[Sorted], b: Parse[Sorted]): Int = {
      // logger.ifDebug("Scoring %s vs %s".format(printDebugParse(a), printDebugParse(b)))

      for {
        aFeature <- a.headOption
        bFeature <- b.headOption
      } {
        var performContainHackCheck = true
        if (req.autocomplete) {
          // NOTE(blackmad): don't remember why I wrote this and it's majorly slowing us down
          // I wrote this so that "united" didn't check if " United Industrial Park" was a child of "United States of America"
          // for the completion of "United" ... this check was here for when we're deciding between "Buenos Aires" and "Buenos Aires"
          // at different admin levels.
          //
          // val aName = bestNameWithMatch(aFeature.fmatch.feature, Some(req.lang), false, Some(aFeature.phrase))
          // val bName = bestNameWithMatch(bFeature.fmatch.feature, Some(req.lang), false, Some(bFeature.phrase))
          // performContainHackCheck = (aName == bName)
        }

        if (performContainHackCheck &&
            aFeature.tokenStart == bFeature.tokenStart && 
            aFeature.tokenEnd == bFeature.tokenEnd &&
            aFeature.fmatch.feature.woeType != YahooWoeType.COUNTRY &&
            bFeature.fmatch.feature.woeType != YahooWoeType.COUNTRY &&
            // if we have a hint that we want one of the types, then let the 
            // scoring happen naturally
            !req.woeHint.asScala.has(aFeature.fmatch.feature.woeType) &&
            !req.woeHint.asScala.has(bFeature.fmatch.feature.woeType)
          ) {
          // if a is a parent of b, prefer b 
          if (aFeature.fmatch.scoringFeatures.parents.asScala.has(bFeature.fmatch.id) &&
            (aFeature.fmatch.scoringFeatures.population * 1.0 / bFeature.fmatch.scoringFeatures.population) > 0.05
          ) {
            logger.ifDebug("Preferring %s because it's a child of %s".format(printDebugParse(a), printDebugParse(b)))
            return -1
          }
          // if b is a parent of a, prefer a
          if (bFeature.fmatch.scoringFeatures.parents.asScala.has(aFeature.fmatch.id) &&
             (bFeature.fmatch.scoringFeatures.population * 1.0 / aFeature.fmatch.scoringFeatures.population) > 0.05
            ) {
            logger.ifDebug("Preferring %s because it's a child of %s".format(printDebugParse(b), printDebugParse(a)))
            return 1
          }
        }
      }

      val scoreA = getScore(a)
      val scoreB = getScore(b)
      if (scoreA == scoreB) {
        (a.headOption.map(_.fmatch.feature.ids.map(_.toString).hashCode).getOrElse(0).toLong -
          b.headOption.map(_.fmatch.feature.ids.map(_.toString).hashCode).getOrElse(0).toLong).signum
      } else {
        scoreB - scoreA
      }
    }
  }

  // Modifies a GeocodeFeature that is about to be returned
  // --set 'name' to the feature's best name in the request context
  // --set 'displayName' to a string that includes names of parents in the request context
  // --filter out the total set of names to a more managable number
  // --add in parent features if needed
  def fixFeature(
      f: GeocodeFeature,
      parents: Seq[GeocodeServingFeature],
      parse: Option[Parse[Sorted]],
      numParentsRequired: Int = 0) {
    // set name
    val name = NameUtils.bestName(f, Some(req.lang), false).map(_.name).getOrElse("")
    f.setName(name)

    // Take the least specific parent, hopefully a state
    // Yields names like "Brick, NJ, US"
    // should already be sorted, so we don't need to do: // .sorted(GeocodeServingFeatureOrdering)
    val parentsToUse: List[GeocodeServingFeature] =
      parents
        .filter(p => p.feature.woeType != YahooWoeType.COUNTRY)
        .filter(p => p.feature.woeType != YahooWoeType.CONTINENT)
        .filter(p => {
          if (f.cc != "US" && f.cc != "CA") {
            p.feature.woeType == YahooWoeType.TOWN || 
            p.feature.woeType == YahooWoeType.SUBURB ||
            (numParentsRequired > 0)
          } else {
            true
          }
        })
        .takeRight(
          if (f.cc == "US" || f.cc == "CA") {
            numParentsRequired + 1
          } else {
            numParentsRequired
          }
        ).toList

    val countryAbbrev: Option[String] = if (f.cc != req.cc) {
      if (f.cc == "GB") {
        Some("UK")
      } else {
        Some(f.cc)
      }
    } else {
      None
    }

    var namesToUse: Seq[(com.foursquare.twofishes.FeatureName, Option[String])] = Nil

    parse.foreach(p => {
      val partsFromParse: Seq[(Option[FeatureMatch], GeocodeServingFeature)] =
        p.map(fmatch => (Some(fmatch), fmatch.fmatch))
      val partsFromParents: Seq[(Option[FeatureMatch], GeocodeServingFeature)] =
       parentsToUse.filterNot(f => partsFromParse.exists(_._2 == f))
        .map(f => (None, f))
      val partsToUse = (partsFromParse ++ partsFromParents).sortBy(_._2)(GeocodeServingFeatureOrdering)
      // logger.ifDebug("parts to use: " + partsToUse)
      var i = 0
      namesToUse = partsToUse.flatMap({case(fmatchOpt, servingFeature) => {
        val name = bestNameWithMatch(servingFeature.feature, Some(req.lang), i != 0,
          fmatchOpt.map(_.phrase))
        i += 1
        name
      }})

      // strip dupe un-matched parts, so we don't have "Istanbul, Istanbul, TR"
      // don't strip out matched parts (that's the isempty check)
      // don't strip out the main feature name (index != 0)
      namesToUse = namesToUse.zipWithIndex.filterNot({case (nameMatch, index) => {
        index != 0 && nameMatch._2.isEmpty && nameMatch._1.name == namesToUse(0)._1.name
      }}).map(_._1)
    
      var (matchedNameParts, highlightedNameParts) = 
        (namesToUse.map(_._1.name),
         namesToUse.map({case(fname, highlightedName) => {
          highlightedName.getOrElse(fname.name)
        }}))

      if (!partsToUse.exists(_._2.feature.woeType == YahooWoeType.COUNTRY)) {
        matchedNameParts ++= countryAbbrev.toList
        highlightedNameParts ++= countryAbbrev.toList
      }
      f.setMatchedName(matchedNameParts.mkString(", "))
      f.setHighlightedName(highlightedNameParts.mkString(", "))
    })

    // possibly clear names
    val names = f.names
    f.setNames(names.filter(n => 
      Option(n.flags).exists(_.contains(FeatureNameFlags.ABBREVIATION)) ||
      n.lang == req.lang ||
      n.lang == "en" ||
      namesToUse.contains(n)
    ))

    val parentNames = parentsToUse.map(p =>
      NameUtils.bestName(p.feature, Some(req.lang), true).map(_.name).getOrElse(""))
       .filterNot(parentName => {
         name == parentName
       })

    var displayNameParts = Vector(name) ++ parentNames
    if (f.woeType != YahooWoeType.COUNTRY) {
      displayNameParts ++= countryAbbrev.toList
    }
    f.setDisplayName(displayNameParts.mkString(", "))
  }

  def featureDistance(f1: GeocodeFeature, f2: GeocodeFeature) = {
    GeoTools.getDistance(
      f1.geometry.center.lat,
      f1.geometry.center.lng,
      f2.geometry.center.lat,
      f2.geometry.center.lng)
  }

  def boundsContains(f1: GeocodeFeature, f2: GeocodeFeature) = {
    Option(f1.geometry.bounds).exists(bb =>
      GeoTools.boundsContains(bb, f2.geometry.center)) ||
    Option(f2.geometry.bounds).exists(bb =>
      GeoTools.boundsContains(bb, f1.geometry.center))
  }

  def parsesNear(p1: Parse[Sorted], p2: Parse[Sorted]): Boolean = {
    (p1.headOption, p2.headOption) match {
      case (Some(sf1), Some(sf2)) => {
        (featureDistance(sf1.fmatch.feature, sf2.fmatch.feature) < 15000) ||
        boundsContains(sf1.fmatch.feature, sf2.fmatch.feature)
      }
      case _ => false
    }
  }
  
  // Two jobs
  // 1) filter out woeRestrict mismatches
  // 2) try to filter out near dupe parses (based on formatting + latlng)
  def filterParses(parses: SortedParseSeq, removeLowRankingParses: Boolean): SortedParseSeq = {
    if (req.debug > 0) {
      logger.ifDebug("have %d parses in filterParses".format(parses.size))
      parses.foreach(s => logger.ifDebug(s.toString))
    }

    var goodParses = if (req.woeRestrict.size > 0) {
      parses.filter(p =>
        p.headOption.exists(f => req.woeRestrict.contains(f.fmatch.feature.woeType))
      )
    } else if (req.autocomplete) {
      parses.filterNot(p =>
        p.headOption.exists(f =>
          f.fmatch.feature.woeType == YahooWoeType.ADMIN1 ||
          f.fmatch.feature.woeType == YahooWoeType.CONTINENT ||
          f.fmatch.feature.woeType == YahooWoeType.COUNTRY
        )
      )
    } else {
      parses
    }
    logger.ifDebug("have %d parses after filtering types/woes/restricts".format(goodParses.size))

    if (removeLowRankingParses) {
      goodParses = goodParses.filter(p => p.headOption.exists(m => {
        m.fmatch.scoringFeatures.population > 50000 || p.length > 1
      }))
      logger.ifDebug("have %d parses after removeLowRankingParses".format(goodParses.size))
    }

    goodParses = goodParses.filter(p => p.headOption.exists(m => m.fmatch.scoringFeatures.canGeocode))

    if (req.isSetAllowedSources()) {
      val allowedSources = req.allowedSources.asScala
      goodParses = goodParses.filter(p =>
        p.headOption.exists(_.fmatch.feature.ids.exists(i => allowedSources.has(i.source)))
      )
    }

    goodParses
  }

  def dedupeParses(parses: SortedParseSeq): SortedParseSeq = {
    val parseIndexToNameMatch: Map[Int, Option[BestNameMatch]] =
      parses.zipWithIndex.map({case (parse, index) => {
        (index,
          parse.headOption.flatMap(f => {
            // en is cheating, sorry
            bestNameWithMatch(f.fmatch.feature, Some("en"), false, Some(f.phrase))
          })
        )
      }}).toMap

    val parseMap: Map[String, Seq[(Parse[Sorted], Int)]] = parses.zipWithIndex.groupBy({case (parse, index) => {
      parseIndexToNameMatch(index).map(_._1.name).getOrElse("")
    }})

    if (req.debug > 0) {
      parseMap.foreach({case(name, parseSeq) => {
        logger.ifDebug("have %d parses for %s".format(parseSeq.size, name))
        parseSeq.foreach(p => {
          logger.ifDebug("%s: %s".format(name, printDebugParse(p._1)))
        })
      }})
    }

    def isAliasName(index: Int): Boolean = {
      parseIndexToNameMatch(index).exists(_._1.flags.contains(
        FeatureNameFlags.ALIAS))
    }

    val dedupedMap = parseMap.mapValues(parsePairs => {
      // see if there's an earlier parse that's close enough,
      // if so, return false
      parsePairs.filterNot({case (parse, index) => {
        parsePairs.exists({case (otherParse, otherIndex) => {
          // the logic here is that an alias name shoudl lose to an unaliased name
          // if we don't have the clause in this line, we end up losing both nearby interps
          ((otherIndex < index && !(isAliasName(otherIndex) && !isAliasName(index)))
            || (!isAliasName(otherIndex) && isAliasName(index))) &&
            parsesNear(parse, otherParse) 
        }})
      }})
    })

    // We have a map of [name -> List[Parse, Int]] ... extract out the parse-int pairs
    // join them, and re-sort by the int, which was their original ordering
    dedupedMap.toList.flatMap(_._2).sortBy(_._2).map(_._1)
  }

  def generateResponse(interpretations: Seq[GeocodeInterpretation]): GeocodeResponse = {
    val resp = new GeocodeResponse()
    resp.setInterpretations(interpretations)
    if (req.debug > 0) {
      resp.setDebugLines(logger.getLines)
    }
    resp
  }

  def printDebugParse(p: Parse[_ <: MaybeSorted]): String = {
    val name = p.flatMap(_.fmatch.feature.names.headOption).map(_.name).mkString(", ")
    // god forgive this line of code
    val id = p.headOption.toList.flatMap(f => Option(f.fmatch.feature.ids)).flatten.headOption.map(fid =>
      "%s:%s".format(fid.source, fid.id)).getOrElse("no:id")
    "%s %s".format(id, name)
  }

  def responseIncludes(include: ResponseIncludes): Boolean = {
    req.responseIncludes.asScala.has(include) ||
      req.responseIncludes.asScala.has(ResponseIncludes.EVERYTHING) ||
      req.full_DEPRECATED
  }

  // This function signature is gross
  // Given a set of parses, create a geocode response which has fully formed
  // versions of all the features in it (names, parents)
  def hydrateParses(
    longest: Int,
    sortedParses: SortedParseSeq,
    parseParams: ParseParams,
    polygonMap: Map[ObjectId, Array[Byte]]
  ): GeocodeResponse = {
    val tokens = parseParams.tokens
    val originalTokens = parseParams.originalTokens
    val tryHard = parseParams.tryHard
    val connectorStart = parseParams.connectorStart
    val connectorEnd = parseParams.connectorEnd
    val hadConnector = parseParams.hadConnector

    // sortedParses.foreach(p => {
    //   logger.ifDebug(printDebugParse(p))
    // })

    val parentIds = sortedParses.flatMap(
      _.headOption.toList.flatMap(_.fmatch.scoringFeatures.parents))
    val parentOids = parentIds.map(parent => new ObjectId(parent))
    logger.ifDebug("parent ids: " + parentOids)

    // possible optimization here: add in features we already have in our parses and don't refetch them
    val parentMap = store.getByObjectIds(parentOids)
    logger.ifDebug(parentMap.toString)

    val what = if (hadConnector) {
      originalTokens.take(connectorStart).mkString(" ")
    } else {
      tokens.take(tokens.size - longest).mkString(" ")
    }
    val where = tokens.drop(tokens.size - longest).mkString(" ")
    logger.ifDebug("%d sorted parses".format(sortedParses.size))
    logger.ifDebug("%s".format(sortedParses))

    var interpretations = sortedParses.map(p => {
      val fmatch = p(0).fmatch
      val feature = p(0).fmatch.feature
      val sortedParents = p(0).fmatch.scoringFeatures.parents.flatMap(parent =>
        parentMap.get(new ObjectId(parent))).sorted(GeocodeServingFeatureOrdering)
      fixFeature(feature, sortedParents, Some(p))
      val interp = new GeocodeInterpretation()
      interp.setWhat(what)
      interp.setWhere(where)
      interp.setFeature(feature)

      val scores = p.scoringFeatures
      interp.setScores(scores)

      if (responseIncludes(ResponseIncludes.WKT_GEOMETRY) ||
          responseIncludes(ResponseIncludes.WKB_GEOMETRY)) {
        polygonMap.get(new ObjectId(fmatch.id)).foreach(wkb => {
          feature.geometry.setWkbGeometry(wkb)
          if (responseIncludes(ResponseIncludes.WKT_GEOMETRY)) {
            val wkbReader = new WKBReader()
            val wktWriter = new WKTWriter()
            val geom = wkbReader.read(wkb)
            feature.geometry.setWktGeometry(wktWriter.write(geom))
          }
        })
      }

      if (responseIncludes(ResponseIncludes.PARENTS)) {
        interp.setParents(sortedParents.map(parentFeature => {
          val sortedParentParents = parentFeature.scoringFeatures.parents.flatMap(parent =>
            parentMap.get(new ObjectId(parent))).sorted
          val feature = parentFeature.feature
          fixFeature(feature, sortedParentParents, None)
          feature
        }))
      }
      interp
    })

    // Find + fix ambiguous names
    // check to see if any of our features are ambiguous, even after deduping (which
    // happens outside this function). ie, there are 3 "Cobble Hill, NY"s. Which
    // are the names we get if we only take one parent component from each.
    // Find ambiguous geocodes, tell them to take more name component
    val ambiguousInterpretationsMap: Map[String, Seq[GeocodeInterpretation]] = 
      interpretations.groupBy(_.feature.displayName).filter(_._2.size > 1)
    val ambiguousInterpretations: Iterable[GeocodeInterpretation] = 
      ambiguousInterpretationsMap.flatMap(_._2).toList
    val ambiguousIdMap: Map[String, Iterable[GeocodeInterpretation]] =
      ambiguousInterpretations.groupBy(_.feature.ids.toString)

    if (ambiguousInterpretations.size > 0) {
      logger.ifDebug("had ambiguous interpretations")
      ambiguousInterpretationsMap.foreach({case (k, v) => logger.ifDebug("have %d of %s".format(v.size, k)) })
      sortedParses.foreach(p => {
        val fmatch = p(0).fmatch
        val feature = p(0).fmatch.feature
        ambiguousIdMap.getOrElse(feature.ids.toString, Nil).foreach(interp => {
          val sortedParents = p(0).fmatch.scoringFeatures.parents
            .flatMap(parent =>
              parentMap.get(new ObjectId(parent))).sorted(GeocodeServingFeatureOrdering)
          fixFeature(interp.feature, sortedParents, Some(p), 2)
        })
      })
    }

    generateResponse(interpretations)
  }

  def geocode(): GeocodeResponse = {
    Stats.incr("geocode-requests", 1)
    val query = req.query
    if (query != null) {
      logger.ifDebug("%s --> %s".format(query, NameNormalizer.normalize(query)))

      var originalTokens =
        NameNormalizer.tokenize(NameNormalizer.normalize(query))

      val spaceAtEnd = query.takeRight(1) == " "

      geocode(originalTokens, spaceAtEnd=spaceAtEnd)
    } else {
      geocode(Nil)
    }
  }

  def deleteCommonWords(tokens: List[String]): List[String] = {
    val commonWords = Set(
      "city", "gemeinde", "canton", "of", "county", "gmina", "stadtteil", "district", "kommune", "prefecture", "contrada",
      "Stazione", "di", "oblast"
    )

    tokens.filterNot(t => commonWords.contains(t))
  } 

  case class ParseParams(
    tokens: List[String] = Nil,
    originalTokens: List[String] = Nil,
    tryHard: Boolean = false,
    connectorStart: Int = 0,
    connectorEnd: Int = 0,
    hadConnector: Boolean = false,
    isRetry: Boolean = false
  )

  def maybeRetryParsing(
    parses: SortedParseSeq,
    parseLength: Int,
    parseParams: ParseParams
  ) = {
    val modifiedTokens = deleteCommonWords(parseParams.originalTokens)
    logger.ifDebug("common words deleted: %s".format(modifiedTokens.mkString(" ")))
    if (modifiedTokens.size != parseParams.originalTokens.size && !parseParams.isRetry) {
      logger.ifDebug("RESTARTING common words query: %s".format(modifiedTokens.mkString(" ")))
      geocode(modifiedTokens, isRetry=true)
    } else {
      buildFinalParses(parses, parseLength, parseParams)
    }
  }

  def buildFinalParses(
    parses: SortedParseSeq,
    parseLength: Int,
    parseParams: ParseParams
  ) = {
    val tokens = parseParams.tokens
    val originalTokens = parseParams.originalTokens
    val tryHard = parseParams.tryHard
    val connectorStart = parseParams.connectorStart
    val connectorEnd = parseParams.connectorEnd

    val removeLowRankingParses = (parseLength != tokens.size && parseLength == 1 && !tryHard)

    // filter out parses that are really the same feature
    val parsesByMainId = parses.groupBy(_.headOption.map(_.fmatch.id))
    val actualParses = 
      parsesByMainId
        .flatMap({case (k, v) => v.sortBy(p => {
          // prefer interpretations that are shorter and don't have reused features
          val dupeWeight = if (parseHasDupeFeature(p)) { 10 } else { 0 }
          p.size + dupeWeight
        }).headOption}).toList

    val sortedParses = actualParses.sorted(new ParseOrdering)

    val filteredParses = filterParses(sortedParses, removeLowRankingParses)

    val maxInterpretations = if (req.maxInterpretations <= 0) {
      3
    } else {
      req.maxInterpretations
    }

    val dedupedParses = if (req.autocomplete) {
      dedupeParses(filteredParses.take(maxInterpretations * 2)).take(maxInterpretations)
    } else {
      dedupeParses(filteredParses.take(maxInterpretations))
    }
    logger.ifDebug("%d parses after deduping".format(dedupedParses.size))
    dedupedParses.foreach(p =>
      logger.ifDebug("deduped parse ids: %s".format(p.map(_.fmatch.id)))
    )

    // TODO: make this configurable
    val sortedDedupedParses: SortedParseSeq = dedupedParses.sorted(new ParseOrdering).take(3)
    val polygonMap: Map[ObjectId, Array[Byte]] = getPolygonMap(
      sortedDedupedParses.map(p => new ObjectId(p(0).fmatch.id)))
    hydrateParses(parseLength, sortedDedupedParses, parseParams, polygonMap)
  }

  def doNormalGeocode(parseParams: ParseParams) = {
    val tokens = parseParams.tokens
    val originalTokens = parseParams.originalTokens
    val tryHard = parseParams.tryHard
    val connectorStart = parseParams.connectorStart
    val connectorEnd = parseParams.connectorEnd
    val hadConnector = parseParams.hadConnector
    val isRetry = parseParams.isRetry

    val cache = generateParses(tokens)

    val validParseCaches: Iterable[(Int, SortedParseSeq)] =
      cache.filter(_._2.nonEmpty)

    if (validParseCaches.size > 0) {
      val longest = validParseCaches.map(_._1).max
      if (hadConnector && longest != tokens.size) {
        generateResponse(Nil)
      } else {
        val longestParses = validParseCaches.find(_._1 == longest).get._2
        if (longest != tokens.size) {
          maybeRetryParsing(longestParses, longest, parseParams)
        } else {
          buildFinalParses(longestParses, longest, parseParams)
        }
      }
    } else {
      generateResponse(Nil)
    }
  }

  def doAutocompleteGeocode(
    spaceAtEnd: Boolean,
    parseParams: ParseParams
  ) = {
    val parses = generateAutoParses(parseParams.tokens, spaceAtEnd)
    if (req.debug > 1) {
      parses.foreach(p => {
        logger.ifDebug("parse ids: %s".format(p.map(_.fmatch.id)))
      })
    }
    buildFinalParses(parses, 0, parseParams)
  }

  def doSlugGeocode(slug: String): GeocodeResponse = {
    val parseParams = ParseParams()

    val featureMap: Map[String, GeocodeServingFeature]  = if (ObjectId.isValid(slug)) {
      val features = store.getByObjectIds(List(new ObjectId(slug)))
      features.map({case (key, value) => (key.toString, value)}).toMap
    } else {
      store.getBySlugOrFeatureIds(List(slug))
    }

    // a parse is still a seq of featurematch, bleh
    val parse = Parse[Sorted](featureMap.get(slug).map(servingFeature => {
      FeatureMatch(0, 0, "", servingFeature)
    }).toList)

    buildFinalParses(List(parse), 0, parseParams)
  }

  def geocode(originalTokens: List[String],
              isRetry: Boolean = false,
              spaceAtEnd: Boolean = false
      ): GeocodeResponse = {
    logger.ifDebug("--> %s".format(originalTokens.mkString("_|_")))

    // This is awful connector parsing
    val connectorStart = originalTokens.findIndexOf(_ == "near")
    val connectorEnd = connectorStart
    val hadConnector = connectorStart != -1

    val tryHard = hadConnector

    val tokens = if (hadConnector) {
      originalTokens.drop(connectorEnd + 1)
    } else { originalTokens }

    val parseParams = ParseParams(
      tokens = tokens,
      originalTokens = originalTokens,
      connectorStart = connectorStart,
      connectorEnd = connectorEnd,
      tryHard = tryHard,
      hadConnector = hadConnector,
      isRetry = isRetry
    )

    // Need to tune the algorithm to not explode on > 10 tokens
    // in the meantime, reject.
    if (tokens.size > 10) {
      throw new Exception("too many tokens")
    }

    if (Option(req.slug).exists(_.nonEmpty)) {
      Stats.time("slug-geocode") {
        doSlugGeocode(req.slug)
      }
    } else if (req.autocomplete) {
      Stats.time("autocomplete-geocode") {
        doAutocompleteGeocode(spaceAtEnd, parseParams)
      }
    } else {
      Stats.time("geocode") {
        doNormalGeocode(parseParams)
      }
    }
  }

  class ReverseGeocodeParseOrdering extends Ordering[Parse[Sorted]] {
    def compare(a: Parse[Sorted], b: Parse[Sorted]): Int = {
      val comparisonOpt = for {
        aFeatureMatch <- a.headOption
        bFeatureMatch <- b.headOption
      } yield {
        val aServingFeature = aFeatureMatch.fmatch
        val bServingFeature = bFeatureMatch.fmatch
        val aWoeTypeOrder = YahooWoeTypes.getOrdering(aServingFeature.feature.woeType)
        val bWoeTypeOrder = YahooWoeTypes.getOrdering(bServingFeature.feature.woeType)
        if (aWoeTypeOrder != bWoeTypeOrder) {
           aWoeTypeOrder - bWoeTypeOrder
        } else {
          bServingFeature.scoringFeatures.boost - 
            aServingFeature.scoringFeatures.boost
        }
      }

      comparisonOpt.getOrElse(0)
    }
  }

  def logDuration[T](ostrichKey: String, what: String)(f: => T): T = {
    val (rv, duration) = Duration.inNanoseconds(f)
    Stats.addMetric(ostrichKey, duration.inMicroseconds.toInt)
    if (req.debug > 0) {
      logger.ifDebug(what + " in %s µs / %s ms".format(duration.inMicroseconds, duration.inMilliseconds))
    }
    rv
  }

  def featureGeometryIntersections(wkbGeometry: Array[Byte], otherGeom: Geometry) = {
    val wkbReader = new WKBReader()
    val geom = wkbReader.read(wkbGeometry)
    (geom, geom.intersects(otherGeom))
  }

  def computeCoverage(
    featureGeometry: Geometry,
    requestGeometry: Geometry
  ): Double = {
    val intersection = featureGeometry.intersection(requestGeometry)
    math.min(1, intersection.getArea() / requestGeometry.getArea())
  }

  lazy val shouldFetchPolygon = 
    responseIncludes(ResponseIncludes.WKB_GEOMETRY) ||
    responseIncludes(ResponseIncludes.WKT_GEOMETRY) ||
    responseIncludes(ResponseIncludes.REVGEO_COVERAGE)

  def getPolygonMap(featureOids: Seq[ObjectId]): Map[ObjectId, Array[Byte]] = {
    if (shouldFetchPolygon) {
      val polygonMapSeq: Seq[(ObjectId, Array[Byte])] = 
        for {
          oid <- featureOids
          polygon <- store.getPolygonByObjectId(oid)
        } yield {
          (oid -> polygon)
        }
      polygonMapSeq.toMap
    } else {
      Map.empty
    }
  }

  def doReverseGeocode(cellids: Seq[Long], otherGeom: Geometry): GeocodeResponse = {
    val cellGeometries: Seq[CellGeometry] = cellids.map(store.getByS2CellId).flatten

    val featureOids: Seq[ObjectId] = {
      if (req.debug > 0) {
        logger.ifDebug("had %d candidates".format(cellGeometries.size))
      }
      (for {
        cellGeometry <- cellGeometries
        if (req.woeRestrict.isEmpty || req.woeRestrict.asScala.has(cellGeometry.woeType))
        if (cellGeometry.wkbGeometry != null)
      } yield {
        val oid = new ObjectId(cellGeometry.getOid())
        if (cellGeometry.isFull) {
          Some(oid)
        } else {
          val (geom, intersects) = logDuration("intersectionCheck", "intersecting %s".format(oid)) {
            featureGeometryIntersections(cellGeometry.getWkbGeometry(), otherGeom)
          }
          if (intersects) {
            Some(oid)
          } else {
            None
          }
        }
      }).flatten
    }

    val servingFeaturesMap: Map[ObjectId, GeocodeServingFeature] =
      store.getByObjectIds(featureOids.toSet.toList)

    // need to get polygons if we need to calculate coverage
    val polygonMap: Map[ObjectId, Array[Byte]] = getPolygonMap(featureOids) 
    val wkbReader = new WKBReader()
    // for each, check if we're really in it
    val parses: SortedParseSeq = servingFeaturesMap.map({ case (oid, f) => {
      val parse = Parse[Sorted](List(FeatureMatch(0, 0, "", f)))
      if (responseIncludes(ResponseIncludes.REVGEO_COVERAGE)) {
        polygonMap.get(oid).foreach(wkb => {
          val geom = wkbReader.read(wkb)
          parse.scoringFeatures.setPercentOfRequestCovered(computeCoverage(geom, otherGeom))
          parse.scoringFeatures.setPercentOfFeatureCovered(computeCoverage(otherGeom, geom))
        })
      }
      parse
    }}).toSeq

    val parseParams = ParseParams()

    val maxInterpretations = if (req.maxInterpretations <= 0) {
      parses.size  
    } else {
      req.maxInterpretations
    }

    val sortedParses = parses.sorted(new ReverseGeocodeParseOrdering).take(maxInterpretations)
    val response = hydrateParses(0, sortedParses, parseParams, polygonMap)
    if (req.debug > 0) {
      val wktWriter = new WKTWriter
      response.setRequestWktGeometry(wktWriter.write(otherGeom))
    }
    response
  }

  def getAllLevels(): Seq[Int] = {
    for {
      level <- store.getMinS2Level.to(store.getMaxS2Level)
      if ((level - store.getMinS2Level) % store.getLevelMod) == 0
    } yield { level }
  }

  def reverseGeocodePoint(ll: GeocodePoint): GeocodeResponse = {
    val levels = getAllLevels()
    logger.ifDebug("doing point revgeo on %s at levels %s".format(ll, levels.mkString(",")))

    val cellids: Seq[Long] = 
      levels.map(level =>
        GeometryUtils.getS2CellIdForLevel(ll.lat, ll.lng, level).id()
      )
    logger.ifDebug("looking up: " + cellids.mkString(" "))

    val geomFactory = new GeometryFactory()
    val point = geomFactory.createPoint(
      new Coordinate(ll.lng, ll.lat)
    )

    doReverseGeocode(cellids, point)
  }

  def doGeometryReverseGeocode(geom: Geometry) = {
    val cellids = logDuration("s2_cover_time", "s2_cover_time") {
      GeometryUtils.coverAtAllLevels(
        geom,
        store.getMinS2Level,
        store.getMaxS2Level,
        Some(store.getLevelMod)
      ).map(_.id())
    }
    doReverseGeocode(cellids, geom)
  }

  def reverseGeocode(): GeocodeResponse = {
    Stats.incr("revgeo-requests", 1)
    if (req.ll != null) {
      if (req.isSetRadius) {
        val sizeDegrees = req.radius / 111319.9
        val gsf = new GeometricShapeFactory()
        gsf.setSize(sizeDegrees)
        gsf.setNumPoints(100)
        gsf.setCentre(new Coordinate(req.ll.lng, req.ll.lat))
        val geom = gsf.createCircle()
        Stats.time("revgeo-geom") {
          doGeometryReverseGeocode(geom)
        }
      } else {
        Stats.time("revgeo-point") {
          reverseGeocodePoint(req.ll)
        }
      }
    } else if (req.bounds != null) {
      val s2rect = GeoTools.boundingBoxToS2Rect(req.bounds)
      val geomFactory = new GeometryFactory()
      val geom = geomFactory.createLinearRing(Array(
        new Coordinate(s2rect.lng.lo, s2rect.lat.lo),
        new Coordinate(s2rect.lng.hi, s2rect.lat.lo),
        new Coordinate(s2rect.lng.hi, s2rect.lat.hi),
        new Coordinate(s2rect.lng.hi, s2rect.lat.lo),
        new Coordinate(s2rect.lng.lo, s2rect.lat.lo)
      ))
      Stats.time("revgeo-geom") {
        doGeometryReverseGeocode(geom)
      }
    } else {
      throw new Exception("no bounds or ll")
    }
  }
}
