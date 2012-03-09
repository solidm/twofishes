 //  Copyright 2012 Foursquare Labs Inc. All Rights Reserved
package com.foursquare.geocoder

import collection.JavaConverters._
import com.twitter.finagle.builder.{ServerBuilder, Server}
import com.twitter.finagle.http.Http
import com.twitter.finagle.thrift.ThriftServerFramedCodec
import com.twitter.finagle.Service
import com.twitter.util.{Future, FuturePool, Throw}
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.apache.thrift.protocol.{TBinaryProtocol, TSimpleJSONProtocol}
import org.apache.thrift.TSerializer
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.util.CharsetUtil

class GeocodeServerImpl extends Geocoder.ServiceIface {
  val mongoFuturePool = FuturePool(Executors.newFixedThreadPool(8))

  def geocode(r: GeocodeRequest): Future[GeocodeResponse] = {
    new GeocoderImpl(mongoFuturePool, new MongoGeocodeStorageService()).geocode(r)
  }
}

class GeocoderHttpService extends Service[HttpRequest, HttpResponse] {
  val diskIoFuturePool = FuturePool(Executors.newFixedThreadPool(4))

  def handleQuery(request: GeocodeRequest): Future[DefaultHttpResponse] = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

    new GeocoderImpl(diskIoFuturePool, new MongoGeocodeStorageService()).geocode(request).map(geocode => {
      val serializer = new TSerializer(new TSimpleJSONProtocol.Factory());
      val json = serializer.toString(geocode);

      response.setContent(ChannelBuffers.copiedBuffer(json, CharsetUtil.UTF_8))
      response
    })
  }

  def apply(request: HttpRequest) = {
    // This is how you parse request parameters
    val queryString = new QueryStringDecoder(request.getUri())
    val params = queryString.getParameters().asScala
    val path = queryString.getPath()

    if (path.startsWith("/static/")) {
      val dataRead = { scala.io.Source.fromInputStream(getClass.getResourceAsStream(path)).mkString }

      diskIoFuturePool(dataRead).map(data => {
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        if (path.endsWith("png")) {
          response.setHeader("Content-Type", "image/png")
        }
        response.setContent(ChannelBuffers.copiedBuffer(data.getBytes()))
        response
      })
    } else {
      (for {
        queries <- params.get("query")
        query <- queries.asScala.lift(0)
      } yield { 
        val request = new GeocodeRequest(query)
        params.get("lang").foreach(_.asScala.headOption.foreach(v =>
          request.setLang(v)))
        params.get("cc").foreach(_.asScala.headOption.foreach(v =>
          request.setCc(v)))
        params.get("ll").foreach(_.asScala.headOption.foreach(v => {
          val ll = v.split(",").toList
          request.setLl(new GeocodePoint(ll(0).toDouble, ll(1).toDouble))
        }))

        handleQuery(request)
      }).getOrElse({
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
        Future.value(response)
      })
    }
  }
}

// object GeocodeThriftServer extends Application {
//   class GeocodeServer extends Geocoder.Iface {
//     override def geocode(r: GeocodeRequest): GeocodeResponse = {
//       new GeocoderImpl(new MongoGeocodeStorageService()).geocode(request)
//     }
//   }

//   def main(args: Array[String]) {
//     try {
//       val serverTransport = new TServerSocket(8080)
//       val processor = new TimeServer.Processor(new GeocodeServer())
//       val protFactory = new TBinaryProtocol.Factory(true, true)
//       val server = new TThreadPoolServer(processor, serverTransport, protFactory)
      
//       println("starting server")
//       server.serve();     
//     } catch { 
//       case x: Exception => x.printStackTrace();
//     }
//   }
// }

object GeocodeFinagleServer {
  def main(args: Array[String]) {
    // Implement the Thrift Interface
    val processor = new GeocodeServerImpl()

    // Convert the Thrift Processor to a Finagle Service
    val service = new Geocoder.Service(processor, new TBinaryProtocol.Factory())

    val server: Server = ServerBuilder()
      .bindTo(new InetSocketAddress(8080))
      .codec(ThriftServerFramedCodec())
      .name("geocoder")
      .build(service)

    val server2: Server = ServerBuilder()
      .bindTo(new InetSocketAddress(8081))
      .codec(Http())
      .name("geocoder-http")
      .build(new GeocoderHttpService())
  }
}

