package com.cloudwick.spark.loganalysis

import java.io.{File, FileNotFoundException}
import java.net.InetAddress

import com.cloudwick.cassandra.schema.{LocationVisit, LogVolume, StatusCount}
import com.cloudwick.cassandra.{Cassandra, CassandraLocationVisitServiceModule, CassandraLogVolumeServiceModule, CassandraStatusCountServiceModule}
import com.cloudwick.logging.LazyLogging
import com.maxmind.geoip2.DatabaseReader.Builder
import com.maxmind.geoip2.exception.AddressNotFoundException
import org.apache.spark.SparkFiles
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.Time
import org.apache.spark.streaming.dstream.DStream
import org.joda.time.format.DateTimeFormat

import scala.concurrent.duration._

/**
 * Log analytics use-case for Apache log-events generated by
 * [[https://github.com/cloudwicklabs/generator CloudwickLabs Generator]]
 *
 * @author ashrith
 */
object LogAnalyzer extends Cassandra with CassandraStatusCountServiceModule
  with CassandraLogVolumeServiceModule with CassandraLocationVisitServiceModule with LazyLogging {

  type StatusHandler = (RDD[StatusCount], Time) => Unit
  type VolumeHandler = (RDD[LogVolume], Time) => Unit
  type LocationHandler = (RDD[LocationVisit], Time) => Unit

  private val logEventPattern = """([\d.]+) (\S+) (\S+) \[(.*)\] "([^\s]+) (/[^\s]*) HTTP/[^\s]+" (\d{3}) (\d+) "([^"]+)" "([^"]+)"""".r
  private val formatter = DateTimeFormat.forPattern("dd/MMM/yyyy:HH:mm:ss Z")

  if (!new File(SparkFiles.getRootDirectory(), "GeoLite2-City.mmdb").exists()) {
    throw new FileNotFoundException("Please pass GeoLite2-City.mmdb using --files from spark-submit")
  }
  private val dbFile = new File(SparkFiles.get("GeoLite2-City.mmdb"))
  private val dbReader = new Builder(dbFile).build()

  /**
   * Installs required cassandra schema
   */
  def createCassandraSchema() = {
    installSchema()
  }

  /**
   * Takes a log event and validates using regex to figure out if the event is in expected format
   * @param le a log event string
   * @return either `LogEvent` (if event is in expected format) or `None`
   */
  def parseLogEvent(le: String): Option[LogEvent] = {
    le match {
      case logEventPattern(ip, ci, ui, ts, rt, rp, rc, rs, r, ua) =>
        Some(LogEvent(ip, ci, ui, formatter.parseDateTime(ts), rt, rp, rc.toInt, rs.toInt, r, ua))
      case _ => None
    }
  }

  /**
   * Prepares the parsed events in this case to remove the None objects from RDD using flatMap
   * @param lines a RDD of lines
   * @return a RDD of LogEvent objects
   */
  def prepareEvents(lines: RDD[String]): RDD[LogEvent] = {
    lines.flatMap(_.split("\\n")).flatMap(parseLogEvent)
  }

  /**
   * Resolves an ip address to geo location using maxmind's geoip2 api
   * @param ip an ip address to resolve
   * @return Location object with resolved country, city, latitude and longitude information
   */
  def resolveIp(ip: String): Option[Location] = {
    val ipAddress = InetAddress.getByName(ip)
    try {
      val response = dbReader.city(ipAddress)
      val country  = response.getCountry.getIsoCode match { case ""|null => "US" case x => x }
      val city = response.getCity.getName match { case ""|null => "<empty>" case x => x }
      Some(
        Location(ip,
          country,
          city,
          response.getLocation.getLatitude,
          response.getLocation.getLongitude))
    } catch {
      case ex: AddressNotFoundException => None
    }

  }

  /**
   * Takes in raw log events, parses them and aggregates the status counts by using status code as
   * the key
   * @param lines RDD of raw log events
   */
  def statusCounter(lines: RDD[String]): RDD[StatusCount] = {
    val events = prepareEvents(lines)

    events.map(event => (event.responseCode, 1L)).reduceByKey(_ + _).map {
      case (statusCode: Int, count: Long) => StatusCount(statusCode, count)
    }
  }

  /**
   * Takes in log events, parses them and counts the number of times a status code has appeared and
   * finally persists the events to cassandra
   * @param lines a DStream of raw log event lines
   * @param handler a function `(RDD[StatusCount], Time) => Unit` to apply on the driver side
   */
  def statusCounter(lines: DStream[String])(handler: StatusHandler): Unit = {
    val statusCounts = lines.transform(rdd => statusCounter(rdd))

    statusCounts.foreachRDD((rdd: RDD[StatusCount], time: Time) => {
      handler(rdd.sortBy(_.statusCode), time) // executed at the driver

      rdd.foreachPartition(partitionRecords => {
        partitionRecords.foreach(statusCountService.update)
      })
    })
  }

  /**
   * Takes in raw log events, parses them and performs aggregations based on the event minute
   * interval, basically provides the number of hits per minute
   * @param lines RDD of raw log evnets
   * @return RDD of LogVolume
   */
  def volumeCounter(lines: RDD[String]): RDD[LogVolume] = {
    val events = prepareEvents(lines)

    events.map { event =>
      val millis = event.timeStamp.getMillis
      val minutes = Duration(millis, MILLISECONDS).toMinutes
      (minutes, 1L)
    }.reduceByKey(_ + _).map {
      case (minute: Long, count: Long) => LogVolume(minute, count)
    }
  }

  /**
   * Takes in log events, parses them and counts the number of events appeared in single minute
   * window and persists the results to cassandra
   * @param lines a DStream of raw log events
   * @param handler a function to apply on the driver side
   */
  def volumeCounter(lines: DStream[String])(handler: VolumeHandler): Unit = {
    val volumeCounts = lines.transform(rdd => volumeCounter(rdd))

    volumeCounts.foreachRDD((rdd: RDD[LogVolume], time: Time) => {
      handler(rdd.sortBy(_.timeStamp), time)

      rdd.foreachPartition(partitionRecords => {
        partitionRecords.foreach(logVolumeService.update)
      })
    })
  }

  /**
   * Take in raw log events, parses them and aggreagtes based on the country & city, to obtain
   * country and city information it performs GeoLocation lookup
   * @param lines RDD of raw log events
   * @return RDD of LocationVisit
   */
  def countryCounter(lines: RDD[String]): RDD[LocationVisit] = {
    val events = prepareEvents(lines)

    events.flatMap { event =>
      resolveIp(event.ip)
    }.map { loc =>
      ((loc.country, loc.city), 1L)
    }.reduceByKey(_ + _).map {
      case((country: String, city: String), count: Long) => LocationVisit(country, city, count)
    }
  }

  /**
   * Performs geo-location lookup based on the ip address of the log event, counts number of
   * (Country, City) counts and persists them to cassandra
   * @param lines a DStream of raw log events
   * @param handler a function to apply on the driver side
   */
  def countryCounter(lines: DStream[String])(handler: LocationHandler): Unit = {
    val countryCounts = lines.transform(rdd => countryCounter(rdd))

    countryCounts.foreachRDD((rdd: RDD[LocationVisit], time: Time) => {
      handler(rdd.sortBy(_.country), time)

      rdd.foreachPartition(partitionRecords => {
        partitionRecords.foreach(locationVisitService.update)
      })
    })
  }
}
