/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.nio.ByteBuffer
import java.util.concurrent.ScheduledExecutorService
import java.util.UUID
import com.amazonaws.{AmazonClientException, AmazonWebServiceRequest, ClientConfiguration}
import com.amazonaws.auth._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.retry.RetryPolicy.RetryCondition
import com.amazonaws.retry.{PredefinedBackoffStrategies, RetryPolicy}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model._
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.amazonaws.services.sqs.model.{
  MessageAttributeValue,
  QueueDoesNotExistException,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats.syntax.either._

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink.SqsClientAndName

/** KinesisSink companion object with factory method */
object KinesisSink {

  case class SqsClientAndName(sqsClient: AmazonSQS, sqsBufferName: String)

  /**
    * Create a KinesisSink and schedule a task to flush its EventStorage
    * Exists so that no threads can get a reference to the KinesisSink
    * during its construction
    */
  def createAndInitialize(
    kinesisConfig: Kinesis,
    bufferConfig: BufferConfig,
    streamName: String,
    sqsBufferName: Option[String],
    executorService: ScheduledExecutorService
  ): Either[Throwable, KinesisSink] = {
    val clients = for {
      credentials <- getProvider(kinesisConfig.aws)
      kinesisClient = createKinesisClient(credentials, kinesisConfig.endpoint, kinesisConfig.region)
      sqsClientAndName <- sqsBuffer(sqsBufferName, credentials, kinesisConfig.region)
      _ = runChecks(kinesisClient, streamName, sqsClientAndName)
    } yield (kinesisClient, sqsClientAndName)

    clients.map {
      case (kinesisClient, sqsClientAndName) =>
        val ks =
          new KinesisSink(
            kinesisClient,
            kinesisConfig,
            bufferConfig,
            streamName,
            executorService,
            sqsClientAndName
          )
        ks.scheduleFlush()

        // When the application is shut down try to send all stored events
        Runtime
          .getRuntime
          .addShutdownHook(new Thread {
            override def run(): Unit = {
              ks.EventStorage.flush()
              ks.shutdown()
            }
          })
        ks
    }
  }

  /** Create an aws credentials provider through env variables and iam. */
  private def getProvider(awsConfig: AWSConfig): Either[Throwable, AWSCredentialsProvider] = {
    def isDefault(key: String): Boolean = key == "default"
    def isIam(key: String): Boolean     = key == "iam"
    def isEnv(key: String): Boolean     = key == "env"

    ((awsConfig.accessKey, awsConfig.secretKey) match {
      case (a, s) if isDefault(a) && isDefault(s) =>
        new DefaultAWSCredentialsProviderChain().asRight
      case (a, s) if isDefault(a) || isDefault(s) =>
        "accessKey and secretKey must both be set to 'default' or neither".asLeft
      case (a, s) if isIam(a) && isIam(s) =>
        InstanceProfileCredentialsProvider.getInstance().asRight
      case (a, s) if isIam(a) && isIam(s) =>
        "accessKey and secretKey must both be set to 'iam' or neither".asLeft
      case (a, s) if isEnv(a) && isEnv(s) =>
        new EnvironmentVariableCredentialsProvider().asRight
      case (a, s) if isEnv(a) || isEnv(s) =>
        "accessKey and secretKey must both be set to 'env' or neither".asLeft
      case _ =>
        new AWSStaticCredentialsProvider(
          new BasicAWSCredentials(awsConfig.accessKey, awsConfig.secretKey)
        ).asRight
    }).leftMap(new IllegalArgumentException(_))
  }

  /**
    * Creates a new Kinesis client.
    * @param provider aws credentials provider
    * @param endpoint kinesis endpoint where the stream resides
    * @param region aws region where the stream resides
    * @return the initialized AmazonKinesisClient
    */
  private def createKinesisClient(
    provider: AWSCredentialsProvider,
    endpoint: String,
    region: String
  ): AmazonKinesis =
    AmazonKinesisClientBuilder
      .standard()
      .withCredentials(provider)
      .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
      .withClientConfiguration(
        new ClientConfiguration().withRetryPolicy(
          new RetryPolicy(
            new RetryCondition {
              override def shouldRetry(
                originalRequest: AmazonWebServiceRequest,
                exception: AmazonClientException,
                retriesAttempted: Int
              ): Boolean =
                retriesAttempted < 10 &&
                  (exception match {
                    case _: ProvisionedThroughputExceededException => false
                    case _                                         => true
                  })
            },
            new PredefinedBackoffStrategies.FullJitterBackoffStrategy(1000, 5 * 3600),
            10,
            true,
            true
          )
        )
      )
      .build()

  /**
    * Check whether a Kinesis stream exists
    *
    * @param name Name of the stream
    * @return Whether the stream exists
    */
  private def streamExists(client: AmazonKinesis, name: String): Boolean =
    try {
      val describeStreamResult = client.describeStream(name)
      val status               = describeStreamResult.getStreamDescription.getStreamStatus
      status == "ACTIVE" || status == "UPDATING"
    } catch {
      case _: ResourceNotFoundException => false
    }

  private def createSqsClient(provider: AWSCredentialsProvider, region: String) =
    Either.catchNonFatal(
      AmazonSQSClientBuilder
        .standard()
        .withRegion(region)
        .withCredentials(provider)
        .withClientConfiguration(
          new ClientConfiguration().withRetryPolicy(
            new RetryPolicy(
              new RetryCondition {
                override def shouldRetry(
                  originalRequest: AmazonWebServiceRequest,
                  exception: AmazonClientException,
                  retriesAttempted: Int
                ): Boolean =
                  retriesAttempted < 10
              },
              new PredefinedBackoffStrategies.FullJitterBackoffStrategy(1000, 5 * 3600),
              10,
              true,
              true
            )
          )
        )
        .build
    )

  def sqsBuffer(
    sqsBufferName: Option[String],
    provider: AWSCredentialsProvider,
    region: String
  ): Either[Throwable, Option[SqsClientAndName]] =
    sqsBufferName match {
      case Some(name) =>
        createSqsClient(provider, region).map(amazonSqs => Some(SqsClientAndName(amazonSqs, name)))
      case None => None.asRight
    }

  /**
    * Check whether an SQS queue (buffer) exists
    * @param name Name of the queue
    * @return Whether the queue exists
    */
  private def sqsQueueExists(client: AmazonSQS, name: String): Boolean =
    try {
      client.getQueueUrl(name)
      true
    } catch {
      case _: QueueDoesNotExistException => false
    }

  private def runChecks(
    kinesisClient: AmazonKinesis,
    streamName: String,
    sqs: Option[SqsClientAndName]
  ): Unit = {
    lazy val log = LoggerFactory.getLogger(getClass())
    val kExists  = streamExists(kinesisClient, streamName)

    if (!kExists) log.error(s"Kinesis stream $streamName doesn't exist or isn't available.")

    // format: off
    sqs match {
      case Some(clientAndName) =>
        if (sqsQueueExists(clientAndName.sqsClient, clientAndName.sqsBufferName)) ()
        else log.error(s"SQS queue ${clientAndName.sqsBufferName} is defined in config file, but does not exist or isn't available.")
      case None =>
        if (kExists) ()
        else log.error(s"SQS buffer is not configured.")
    }
    // format: on
  }
}

/**
  * Kinesis Sink for the Scala collector.
  */
class KinesisSink private (
  client: AmazonKinesis,
  kinesisConfig: Kinesis,
  bufferConfig: BufferConfig,
  streamName: String,
  executorService: ScheduledExecutorService,
  maybeSqs: Option[SqsClientAndName]
) extends Sink {
  // Records must not exceed MaxBytes - 1MB (for Kinesis)
  // When SQS buffer is enabled MaxBytes has to be 256k,
  // but we encode the message with Base64 for SQS, so the limit drops to 192k
  val SqsLimit          = 192000 // 256000 / 4 * 3
  val KinesisLimit      = 1000000
  override val MaxBytes = if (maybeSqs.isDefined) SqsLimit else KinesisLimit
  val BackoffTime       = 3000L

  val ByteThreshold   = bufferConfig.byteLimit
  val RecordThreshold = bufferConfig.recordLimit
  val TimeThreshold   = bufferConfig.timeLimit

  private val maxBackoff      = kinesisConfig.backoffPolicy.maxBackoff
  private val minBackoff      = kinesisConfig.backoffPolicy.minBackoff
  private val randomGenerator = new java.util.Random()

  log.info("Creating thread pool of size " + kinesisConfig.threadPoolSize)
  maybeSqs match {
    case Some(sqs) =>
      log.info(
        s"SQS buffer for '$streamName' Kinesis sink is set up as: ${sqs.sqsBufferName}."
      )
    case None =>
      log.warn(
        s"No SQS buffer for surge protection set up (consider setting a SQS Buffer in config.hocon)."
      )
  }

  implicit lazy val ec = concurrent.ExecutionContext.fromExecutorService(executorService)

  /**
    * Recursively schedule a task to send everthing in EventStorage
    * Even if the incoming event flow dries up, all stored events will eventually get sent
    * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
    * @param interval When to schedule the next flush
    */
  def scheduleFlush(interval: Long = TimeThreshold): Unit = {
    executorService.schedule(
      new Thread {
        override def run(): Unit = {
          val lastFlushed = EventStorage.getLastFlushTime()
          val currentTime = System.currentTimeMillis()
          if (currentTime - lastFlushed >= TimeThreshold) {
            EventStorage.flush()
            scheduleFlush(TimeThreshold)
          } else {
            scheduleFlush(TimeThreshold + lastFlushed - currentTime)
          }
        }
      },
      interval,
      MILLISECONDS
    )
    ()
  }

  // 'key' is used to populate the 'kinesisKey' message attribute for SQS
  // and as partition key for Kinesis
  case class Event(msg: ByteBuffer, key: String)

  object EventStorage {
    private var storedEvents              = List.empty[Event]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity
      if (eventSize >= MaxBytes) {
        log.error(
          s"Record of size $eventSize bytes is too large - must be less than $MaxBytes bytes"
        )
      } else {
        synchronized {
          storedEvents = Event(eventBytes, key) :: storedEvents
          byteCount += eventSize
          if (storedEvents.size >= RecordThreshold || byteCount >= ByteThreshold) {
            flush()
          }
        }
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.reverse
        storedEvents = Nil
        byteCount    = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sendBatch(eventsToSend)
    }

    def getLastFlushTime(): Long = lastFlushedTime
  }

  def storeRawEvents(events: List[Array[Byte]], key: String): List[Array[Byte]] = {
    events.foreach(e => EventStorage.store(e, key))
    Nil
  }

  def scheduleBatch(batch: List[Event], lastBackoff: Long = minBackoff): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(new Thread {
      override def run(): Unit =
        sendBatch(batch, nextBackoff)
    }, lastBackoff, MILLISECONDS)
    ()
  }

  /**
    *  Max number of retries is unlimitted, so when Kinesis stream is under heavy load,
    *  the events accumulate in collector memory for later retries. The fix for this is to use
    *  sqs queue as a buffer and sqs2kinesis to move events back from sqs queue to kinesis stream.
    *  Consider using sqs buffer in heavy load scenarios.
    *
    */
  def sendBatch(batch: List[Event], nextBackoff: Long = minBackoff): Unit =
    if (batch.nonEmpty) {
      log.info(s"Writing ${batch.size} Thrift records to Kinesis stream ${streamName}")

      multiPut(streamName, batch).onComplete {
        case Success(s) => {
          val results      = s.getRecords.asScala.toList
          val failurePairs = batch.zip(results).filter(_._2.getErrorMessage != null)
          log.info(
            s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records"
          )
          if (failurePairs.nonEmpty) {
            failurePairs.foreach(f =>
              log.error(
                s"Record failed with error code [${f._2.getErrorCode}] and message [${f._2.getErrorMessage}]"
              )
            )
            val failures      = failurePairs.map(_._1)
            val retryErrorMsg = s"Retrying all failed records in $nextBackoff milliseconds..."
            sendToSqsOrRetryToKinesis(failures, nextBackoff)(retryErrorMsg)
          }
        }
        case Failure(f) => {
          log.error("Writing failed.", f)
          val retryErrorMsg = s"Retrying in $nextBackoff milliseconds..."
          sendToSqsOrRetryToKinesis(batch, nextBackoff)(retryErrorMsg)
        }
      }
    }

  private def sendToSqsOrRetryToKinesis(
    failures: List[Event],
    nextBackoff: Long
  )(
    retryErrorMsg: String
  ): Unit =
    maybeSqs match {
      case Some(sqs) =>
        log.info(
          s"Sending ${failures.size} events from a batch to SQS buffer queue: ${sqs.sqsBufferName}"
        )
        putToSqs(sqs, failures)
        ()
      case None =>
        log.error(retryErrorMsg)
        log.warn(
          s"${failures.size} failed events scheduled for retry (consider setting a SQS Buffer in config.hocon)"
        )
        scheduleBatch(failures, nextBackoff)
    }

  private def putToSqs(sqs: SqsClientAndName, batch: List[Event]): Future[Unit] =
    Future {
      log.info(s"Writing ${batch.size} messages to SQS queue: ${sqs.sqsBufferName}")
      val MaxSqsBatchSize = 10
      batch.map(toSqsBatchEntry).grouped(MaxSqsBatchSize).foreach { batchEntryGroup =>
        sendToSqs(sqs, batchEntryGroup).transform {
          case failure @ Failure(ex) =>
            log.info(s"Sending to sqs failed with exception: $ex")
            failure
          case s @ Success(_) => s
        }
      }
    }

  private def toSqsBatchEntry(event: Event): SendMessageBatchRequestEntry = {
    val b64EncodedMsg = encode(event.msg)
    // The UUID is not used anywhere currently but is required by the constructor
    new SendMessageBatchRequestEntry(UUID.randomUUID.toString, b64EncodedMsg).withMessageAttributes(
      Map(
        "kinesisKey" ->
          new MessageAttributeValue().withDataType("String").withStringValue(event.key)
      ).asJava
    )
  }

  private def createBatchRequest(queueUrl: String, batch: List[SendMessageBatchRequestEntry]) =
    new SendMessageBatchRequest().withQueueUrl(queueUrl).withEntries(batch.asJava)

  private def sendToSqs(
    sqs: SqsClientAndName,
    batchEntryGroup: List[SendMessageBatchRequestEntry]
  ) =
    Future {
      val batchRequest = createBatchRequest(sqs.sqsBufferName, batchEntryGroup)
      val res          = sqs.sqsClient.sendMessageBatch(batchRequest)
      val failed       = res.getFailed().asScala
      if (failed.nonEmpty) {
        // It could be improved even more by storing events (that failed to be sent to SQS) to some persistance storage
        val errors = failed.map(_.toString).mkString(", ")
        log.error(
          s"Sending to SQS queue [${sqs.sqsBufferName}] failed with errors [$errors]. Dropping events."
        )
        log.info(
          s"${res.getSuccessful.size} out of ${batchEntryGroup.size} from batch was successfully send to SQS queue: ${sqs.sqsBufferName}"
        )
      } else
        log.info(
          s"Batch of ${batchEntryGroup.size} was successfully send to SQS queue: ${sqs.sqsBufferName}."
        )
    }

  private def encode(bufMsg: ByteBuffer): String = {
    val buffer = java.util.Base64.getEncoder.encode(bufMsg)
    new String(buffer.array())
  }

  private def multiPut(name: String, batch: List[Event]): Future[PutRecordsResult] =
    Future {
      val putRecordsRequest = {
        val prr = new PutRecordsRequest()
        prr.setStreamName(name)
        val putRecordsRequestEntryList = batch.map { event =>
          val prre = new PutRecordsRequestEntry()
          prre.setPartitionKey(event.key)
          prre.setData(event.msg)
          prre
        }
        prr.setRecords(putRecordsRequestEntryList.asJava)
        prr
      }
      client.putRecords(putRecordsRequest)
    }

  /**
    * How long to wait before sending the next request
    * @param lastBackoff The previous backoff time
    * @return Minimum of maxBackoff and a random number between minBackoff and three times lastBackoff
    */
  private def getNextBackoff(lastBackoff: Long): Long =
    (minBackoff + randomGenerator.nextDouble() * (lastBackoff * 3 - minBackoff)).toLong.min(maxBackoff)

  def shutdown(): Unit = {
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }
}
