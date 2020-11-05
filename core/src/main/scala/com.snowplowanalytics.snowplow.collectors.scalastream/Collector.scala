/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, Http2}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import org.slf4j.LoggerFactory
import pureconfig._
import pureconfig.generic.{FieldCoproductHint, ProductHint}
import pureconfig.generic.auto._
import metrics._
import model._

// Main entry point of the Scala collector.
trait Collector {

  lazy val log = LoggerFactory.getLogger(getClass())

  implicit def hint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))
  implicit val _ = new FieldCoproductHint[SinkConfig]("enabled")

  def parseConfig(args: Array[String]): (CollectorConfig, Config) = {
    case class FileConfig(config: File = new File("."))
    val parser = new scopt.OptionParser[FileConfig](generated.BuildInfo.name) {
      head(generated.BuildInfo.name, generated.BuildInfo.version)
      help("help")
      version("version")
      opt[File]("config").required().valueName("<filename>")
        .action((f: File, c: FileConfig) => c.copy(f))
        .validate(f =>
          if (f.exists) success
          else failure(s"Configuration file $f does not exist")
        )
    }

    val conf = parser.parse(args, FileConfig()) match {
      case Some(c) => ConfigFactory.parseFile(c.config).resolve()
      case None    => ConfigFactory.empty()
    }

    if (!conf.hasPath("collector")) {
      System.err.println("configuration has no \"collector\" path")
      System.exit(1)
    }

    (loadConfigOrThrow[CollectorConfig](conf.getConfig("collector")), conf)
  }

  def run(collectorConf: CollectorConfig, akkaConf: Config, sinks: CollectorSinks): Unit = {

    implicit val system = ActorSystem.create("scala-stream-collector", akkaConf)
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val collectorRoute = new CollectorRoute {
      override def collectorService = new CollectorService(collectorConf, sinks)
    }

    val prometheusMetricsService = new PrometheusMetricsService(collectorConf.prometheusMetrics)

    val metricsRoute = new MetricsRoute {
      override def metricsService: MetricsService = prometheusMetricsService
    }

    val metricsDirectives = new MetricsDirectives {
      override def metricsService: MetricsService = prometheusMetricsService
    }

    val routes =
      if (collectorConf.prometheusMetrics.enabled)
        metricsRoute.metricsRoute ~ metricsDirectives.logRequest(collectorRoute.collectorRoute)
      else collectorRoute.collectorRoute

    val gRPCRoutes: Route = routes

    lazy val redirectRoutes =
      scheme("http") {
        extract(_.request.uri) { uri =>
          redirect(
            uri.copy(scheme = "https").withPort(collectorConf.ssl.port),
            StatusCodes.MovedPermanently
          )
        }
      }

    def bind(
        rs: Route,
        interface: String,
        port: Int,
        connectionContext: ConnectionContext = ConnectionContext.noEncryption()
    ) =
      Http().bindAndHandle(rs, interface, port, connectionContext)
        .map { binding =>
          log.info(s"REST interface bound to ${binding.localAddress}")
        } recover { case ex =>
          log.error( "REST interface could not be bound to " +
            s"${collectorConf.interface}:${collectorConf.port}", ex.getMessage)
        }

    def bindHttp2(
        rs: Route,
        interface: String,
        port: Int,
        connectionContext: ConnectionContext = SSLConfig.secureConnectionContext(system, AkkaSSLConfig())
    ) =
      Http2().bindAndHandleAsync(Route.asyncHandler(rs), interface, port, connectionContext)
        .map { binding =>
          log.info(s"gRPC interface bound to ${binding.localAddress}")
        } recover { case ex =>
        log.error( "gRPC interface could not be bound to " +
          s"$interface:$port", ex.getMessage)
      }

    lazy val secureEndpoint =
      bind(routes,
           collectorConf.interface,
           collectorConf.ssl.port,
           SSLConfig.secureConnectionContext(system, AkkaSSLConfig())
      )

    lazy val unsecureEndpoint = (routes: Route) =>
      bind(routes, collectorConf.interface, collectorConf.port)

    lazy val http2GrpcEndpoint =
      bindHttp2(
        gRPCRoutes,
        collectorConf.interface,
        collectorConf.grpc.port
      )

    collectorConf.ssl match {
      case SSLConfig(true, true, _) =>
        unsecureEndpoint(redirectRoutes)
        secureEndpoint
        http2GrpcEndpoint
        ()
      case SSLConfig(true, false, _) =>
        unsecureEndpoint(routes)
        secureEndpoint
        http2GrpcEndpoint
        ()
      case _ =>
        unsecureEndpoint(routes)
        http2GrpcEndpoint
        ()
    }
  }
}
