package npl.de.labs

import java.util.concurrent.TimeUnit

import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.{DatumReader, Decoder, DecoderFactory}
import org.apache.avro.specific.SpecificDatumReader
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.influxdb.InfluxDBFactory
import org.influxdb.dto.Point
import org.json4s._
import org.json4s.native.JsonMethods._

import scala.io.Source
import scala.util.{Success, Try}


object KafkaStream extends App with Logging {

  //read avro schema file
  val schemaString = Source.fromURL(getClass.getResource("/event.avsc")).mkString
  val schema: Schema = new Schema.Parser().parse(schemaString)
  val avroReader: DatumReader[GenericRecord] = new SpecificDatumReader[GenericRecord](schema)

  val kafkaParams = Map[String, Object](
    "bootstrap.servers" -> "35.187.190.106:6667",
    "key.deserializer" -> classOf[StringDeserializer],
    "value.deserializer" -> classOf[ByteArrayDeserializer],
    "group.id" -> "spark-reader",
    "auto.offset.reset" -> "latest",
    "enable.auto.commit" -> (false: java.lang.Boolean)
  )

  val topics = Array("de.andrei.malov")

  val conf = new SparkConf().setAppName("KafkaSparkGrafana")
  val ssc = new StreamingContext(conf, Seconds(5))

  val stream = KafkaUtils.createDirectStream[String, Array[Byte]](
    ssc,
    PreferConsistent,
    Subscribe[String, Array[Byte]](topics, kafkaParams)
  )

  val influxDB = InfluxDBFactory.connect("http://35.187.190.106:8086", "test", "test")
    .setDatabase("grafana")
    .setRetentionPolicy("default")

  def decodeAvro(message: Array[Byte]) = {
    // Deserialize and create generic record
    if (!message.isEmpty) {
      val decoder: Decoder = DecoderFactory.get().binaryDecoder(message, null)
      avroReader.read(null, decoder).toString
    }
    else
      throw new Exception("empty message")
  }

  def sendToInflux(counter: (String, Int)): Unit = counter match {
    case (token, cnt) if token!="" && cnt>0 =>
      val p = Point.measurement("words")
        .time(System.currentTimeMillis(), TimeUnit.MILLISECONDS)
        .addField("token", counter._1)
        .addField("cnt", counter._2)
        .tag("token-tag", counter._1)
        .build()
      log.info(s">>> point : {${p.lineProtocol()}}  , $counter")
      influxDB.write(p)
    case _ => ()
  }


  stream.map(record => {
    Try {
      parse(decodeAvro(record.value())) \ "location"
    }
  }).
    map {
      case Success(JString(loc)) => Some(new java.net.URI(loc).getPath)
      case x => None
    }.
    flatMap {
      case Some(word) =>
        print(">>> got WORD:  ", word)
        word.split("\\W+").map(token => (token, 1)).toList
      case None => List(("", 0))
    }.
    filter {
      case (_, cnt) => cnt > 0
    }.
    reduceByKey(_ + _).
    map(sendToInflux).
    map(print(">>> reduced results:  ", _)).
    print()

  ssc.start()
  ssc.awaitTerminationOrTimeout(timeout = args(0).toInt * 1000)

}
