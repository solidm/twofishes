echo "
import com.mongodb.casbah.Imports._
MongoGeocodeDAO.find(MongoDBObject()).foreach(f => {
if (List(YahooWoeType.TOWN, YahooWoeType.SUBURB, YahooWoeType.COUNTRY, YahooWoeType.ADMIN1, YahooWoeType.ADMIN2).contains(f.woeType)) {
  if (f.population.getOrElse(0) > 0 || f.boost.getOrElse(0) > 0) {
    val id = f.ids.find(_.startsWith(\"geonameid\")).getOrElse(f.ids(0))
    GeonamesParser.missingSlugList.add(id)
  }
}
})
GeonamesParser.buildMissingSlugs" | ./sbt indexer/console > output.txt
