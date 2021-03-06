// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes.util

import com.foursquare.twofishes.{FeatureName, FeatureNameFlags, GeocodeFeature, YahooWoeType, util}
import com.foursquare.twofishes.util.Lists.Implicits._
import scala.collection.JavaConversions._

object SlugBuilder {
  import NameFormatter.FormatPattern

  // do something special with CN-14 (tibet) in the parents, ditto taiwan

  val patterns = List( 
    // FormatPattern("{COUNTRY+ABBR}"),
    // FormatPattern("{COUNTRY+ABBR}/{ADMIN1+ABBR}", countries = List("US", "CA")),
    // FormatPattern("{COUNTRY+ABBR}/{ADMIN1}"),
    // FormatPattern("{FEATURE}-{ADMIN1+ABBR}-{COUNTRY+ABBR}", countries = List("US", "CA")),
    // FormatPattern("{FEATURE}-{ADMIN2}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{ADMIN2}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN2}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{TOWN}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{TOWN}-{ADMIN2}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{TOWN}-{ADMIN3}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{TOWN}-{ADMIN3}-{ADMIN2}-{ADMIN1+ABBR}-{COUNTRY+ABBR}"),
    // FormatPattern("{FEATURE}-{COUNTRY+ABBR}")
    FormatPattern("{COUNTRY}"),
    FormatPattern("{COUNTRY}/{ADMIN1}", countries = List("US", "CA")),
    FormatPattern("{COUNTRY}/{ADMIN1}"),
    FormatPattern("{FEATURE}-{ADMIN1}", countries = List("US", "CA")),
    FormatPattern("{FEATURE}-{ADMIN1}-{COUNTRY}", countries = List("US", "CA")),
    FormatPattern("{FEATURE}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN2}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN2}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{ADMIN3}-{ADMIN2}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{TOWN}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{TOWN}-{ADMIN2}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{TOWN}-{ADMIN3}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{TOWN}-{ADMIN3}-{ADMIN2}-{ADMIN1}-{COUNTRY}"),
    FormatPattern("{FEATURE}-{COUNTRY}")
  )

  def normalize(s: String): String = {
    s.toLowerCase
      .replaceAll("['\u2018\u2019\\.\u2013]", "")
      .replaceAll("[\\p{Punct}&&[^-/]]", "")
      .replace(" ", "-")
  }

  def makePossibleSlugs(
    feature: GeocodeFeature,
    parents: List[GeocodeFeature]
  ): List[String] = {
    patterns.flatMap(p => NameFormatter.format(p, feature, parents, Some("en")))
      .map(normalize).map(NameNormalizer.deaccent)
  }
}

object NameFormatter {
  // patterns should look like "{TOWN}, {STATE+ABBR}"
  case class FormatPattern(
    pattern: String,
    // empty means all countries are acceptable
    countries: List[String] = Nil,
    // empty means all languages are acceptable
    languages: List[String] = Nil

  )

  def format(
    patterns: List[FormatPattern],
    feature: GeocodeFeature,
    parents: List[GeocodeFeature],
    lang: Option[String]
  ): Option[String] = {
    patterns.view.flatMap(p => format(p, feature, parents, lang)).headOption
  }

  case class WoeToken(
    woeType: YahooWoeType,
    preferAbbrev: Boolean
  )

  case class WoeTokenMatch(
    woeToken: WoeToken,
    name: String
  ) {
    def applyToString(input: String): String = {
      val replaceString = {
        if (woeToken.preferAbbrev) {
          "{%s+ABBR}".format(woeToken.woeType.toString)
        } else {
          "{%s}".format(woeToken.woeType.toString)
        }
      }

      input.replace(replaceString, name)
    }
  }

  def format(pattern: FormatPattern,
    feature: GeocodeFeature,
    parents: List[GeocodeFeature],
    lang: Option[String]
  ): Option[String] = {
    //val possibleTokens = com.foursquare.twofishes.YahooWoeType.values.asScala.map(_.name)
    if (pattern.countries.isEmpty || pattern.countries.has(feature.cc)) {
      val re = "\\{([^\\{]+)\\}".r
      val woeTokenStrings = re.findAllIn(pattern.pattern).collect{case re(m) => m}.toList
      val hasGeneralFeatureToken = woeTokenStrings.exists(_ == "FEATURE")
      val woeTokens = woeTokenStrings.filterNot(_ == "FEATURE").map(token => {
        val parts = token.split("\\+")
        val woeType = parts(0)
        val preferAbbrev = parts.lift(1).getOrElse("") == "ABBR"
        WoeToken(YahooWoeType.valueOf(woeType), preferAbbrev)
      })

      // println("looking at pattern: %s".format(pattern.pattern))
      // println("woeTokenString: %s".format(woeTokenStrings.mkString(", ")))

      // see if we can find all the types
      val featuresToSearch = parents ++ (if (!hasGeneralFeatureToken) { List(feature) } else { Nil })
      var usedPrimaryFeature = false

      val tokenMatches = for {
        woeToken <- woeTokens
        matchFeature <- featuresToSearch.find(_.woeType == woeToken.woeType)
        name <- NameUtils.bestName(matchFeature, lang, woeToken.preferAbbrev)
      } yield {
        if (matchFeature == feature) {
          usedPrimaryFeature
        }
        WoeTokenMatch(woeToken, name.name)
      }

      // println("had %d woeTokens and %d matches".format(tokenMatches.size, woeTokens.size))

      if (!usedPrimaryFeature && !hasGeneralFeatureToken) {
        None
      } else if (tokenMatches.size == woeTokens.size) {
        var finalString = pattern.pattern
        tokenMatches.foreach(tokenMatch => {
          finalString = tokenMatch.applyToString(finalString)
        })

        if (hasGeneralFeatureToken) {
          NameUtils.bestName(feature, lang, preferAbbrev = false).map(bestName =>
            finalString.replace("{FEATURE}", bestName.name)
          )
        } else {
          Some(finalString)
        }
      } else {
        // didn't match all format tokens, give up
        None
      }
    } else {
      None
    }
  }
}

trait NameUtils {
  // Given an optional language and an abbreviation preference, find the best name
  // for a feature in the current context.
  class FeatureNameComparator(lang: Option[String], preferAbbrev: Boolean) extends Ordering[FeatureName] {
    def compare(a: FeatureName, b: FeatureName) = {
      scoreName(b) - scoreName(a)
    }

    def scoreName(name: FeatureName): Int = {
      var score = 0
      if (Option(name.flags).exists(_.contains(FeatureNameFlags.COLLOQUIAL))) {
        score += 10
      }
      if (Option(name.flags).exists(_.contains(FeatureNameFlags.PREFERRED))) {
        score += 1
      }
      if (Option(name.flags).exists(_.contains(FeatureNameFlags.ALIAS))) {
        score -= 1
      }
      if (lang.exists(_ == name.lang)) {
        score += 2
      }
      if (Option(name.flags).exists(_.contains(FeatureNameFlags.ABBREVIATION) && preferAbbrev)) {
        score += 4
      }
      score
    }
  }

  def bestName(
    f: GeocodeFeature,
    lang: Option[String],
    preferAbbrev: Boolean
  ): Option[FeatureName] = {
    if (preferAbbrev && f.woeType == YahooWoeType.COUNTRY) {
      f.names.find(n => n.name.size == 2 && Option(n.flags).exists(_.contains(FeatureNameFlags.ABBREVIATION)))
    } else {
      f.names.sorted(new FeatureNameComparator(lang, preferAbbrev)).headOption
    }
  }

  type BestNameMatch = (FeatureName, Option[String])

  def bestName(
    f: GeocodeFeature,
    lang: Option[String],
    preferAbbrev: Boolean,
    matchedStringOpt: Option[String],
    debugLevel: Int,
    logger: TwofishesLogger
  ): Option[BestNameMatch] = {
    val ret = matchedStringOpt.flatMap(matchedString => {
      val namesNormalized = f.names.map(n => {
        (n, NameNormalizer.normalize(n.name))
      })

      val exactMatchNameCandidates = namesNormalized.filter(_._2 == matchedString).map(_._1)
      val prefixMatchNameCandidates = namesNormalized.filter(_._2.startsWith(matchedString)).map(_._1)

      val nameCandidates = if (exactMatchNameCandidates.isEmpty) {
        prefixMatchNameCandidates
      } else {
        exactMatchNameCandidates
      }

      val bestNameMatch = nameCandidates.sorted(new FeatureNameComparator(lang, preferAbbrev)).headOption
      if (debugLevel > 1) {
        logger.ifDebug("name candidates: " + nameCandidates)
        logger.ifDebug("best name match: " + bestNameMatch)
      }
      bestNameMatch.map(name => 
          (name,
            Some("<b>" + name.name.take(matchedString.size) + "</b>" + name.name.drop(matchedString.size))
      )) orElse {bestName(f, lang, preferAbbrev).map(name => {
        val normalizedName = NameNormalizer.normalize(name.name)
        val index = normalizedName.indexOf(matchedString)
        if (index > -1) {
          val before = name.name.take(index)
          val matched = name.name.drop(index).take(matchedString.size)
          val after = name.name.drop(index + matchedString.size)
          (name, Some("%s<b>%s</b>%s".format(before, matched, after)))
        } else {
          (name, None)
        }
      })}
    }) orElse { bestName(f, lang, preferAbbrev).map(n => (n, None)) }

    ret
  }
}

object NameUtils extends NameUtils
