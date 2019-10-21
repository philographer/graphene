package com.graphene.writer.store.key

import com.graphene.writer.input.GrapheneMetric
import com.graphene.writer.store.KeyStoreHandler
import com.graphene.writer.store.key.model.ElasticsearchFactory
import com.graphene.writer.store.key.model.ElasticsearchKeyStoreHandlerProperty
import com.graphene.writer.store.key.rotator.KeyRotator
import com.graphene.writer.store.key.rotator.SimpleKeyRotator
import com.graphene.writer.util.NamedThreadFactory
import net.iponweb.disthene.reader.utils.DateTimeUtils
import org.apache.log4j.Logger
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RequestOptions
import org.elasticsearch.client.RestHighLevelClient
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

// TODO duplicated branch node removing logic
abstract class AbstractElasticsearchKeyStoreHandler(
  private val elasticsearchFactory: ElasticsearchFactory,
  private val property: ElasticsearchKeyStoreHandlerProperty
) : KeyStoreHandler, Runnable {

  private val logger = Logger.getLogger(SimpleKeyStoreHandler::class.java)

  private lateinit var client: RestHighLevelClient
  private lateinit var keyRotator: KeyRotator
  private lateinit var keyStoreScheduler: ScheduledExecutorService
  private lateinit var keyRotatorScheduler: ScheduledExecutorService
  private lateinit var multiGetRequestContainer: MultiGetRequestContainer
  private lateinit var index: String
  private lateinit var type: String

  private var lastFlushTimeSeconds = DateTimeUtils.currentTimeSeconds()
  private var batchSize: Int = 0
  private var flushInterval: Long = 0

  private val metrics = ConcurrentLinkedQueue<GrapheneMetric>()

  @PostConstruct
  fun init() {
    client = elasticsearchFactory.restHighLevelClient()
    keyRotator = SimpleKeyRotator(property, elasticsearchFactory)

    index = property.index
    type = property.type
    multiGetRequestContainer = MultiGetRequestContainer()
    batchSize = property.bulk!!.actions
    flushInterval = property.bulk!!.interval

    // How to abstract this method calls
    // for index or alias is empty
    client.indices().putTemplate(createTemplateIfNotExists(), RequestOptions.DEFAULT)
    createIndexIfNotExists(property.index)
    keyRotator.run()

    keyStoreScheduler = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory(SimpleKeyStoreHandler::class.simpleName!!))
    keyStoreScheduler.scheduleWithFixedDelay(this, 3_000, 500, TimeUnit.MILLISECONDS)

    keyRotatorScheduler = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory(KeyRotator::class.simpleName!!))
    keyRotatorScheduler.scheduleWithFixedDelay(keyRotator, 3_000, 60_000, TimeUnit.MILLISECONDS)
  }

  override fun handle(grapheneMetric: GrapheneMetric) {
    metrics.offer(grapheneMetric)
  }

  override fun run() {
    try {
      val metric = metrics.poll()
      if (Objects.nonNull(metric)) {
        addToBatch(metric)
      }

      if (0 < multiGetRequestContainer.size()) {
        flush()
      }
    } catch (e: Exception) {
      logger.error("Encountered error in busy loop: ", e)
    }
  }

  private fun addToBatch(metric: GrapheneMetric) {
    multiGetRequestContainer.add(index, type, metric)

    if (batchSize <= multiGetRequestContainer.size() || lastFlushTimeSeconds < DateTimeUtils.currentTimeSeconds() - flushInterval) {
      flush()
    }
  }

  private fun flush() {
    createIndexIfNotExists(property.index)

    val multiGetResponse = client.mget(multiGetRequestContainer.multiGetRequest, RequestOptions.DEFAULT)
    val bulkRequest = BulkRequest()

    for (response in multiGetResponse.responses) {
      if (response.response.isExists || response.isFailed) {
        continue
      }

      val metric = multiGetRequestContainer.metrics[response.id]
      bulkRequest.add(mapToIndexRequests(metric))
    }

    if (bulkRequest.requests().isEmpty()) {
      return
    }

    val bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT)
    if (bulkResponse.hasFailures()) {
      logger.error("Fail to index metric key, reason : ${bulkResponse.buildFailureMessage()}")
    }
    multiGetRequestContainer = MultiGetRequestContainer()
    lastFlushTimeSeconds = DateTimeUtils.currentTimeSeconds()
  }

  private inner class MultiGetRequestContainer(
    val multiGetRequest: MultiGetRequest = MultiGetRequest(),
    val metrics: MutableMap<String, GrapheneMetric> = mutableMapOf()
  ) {

    fun add(index: String, type: String, metric: GrapheneMetric) {
      metrics[metric.getId()] = metric
      multiGetRequest.add(MultiGetRequest.Item(index, type, metric.getId()))
    }

    fun size(): Int {
      return metrics.size
    }
  }

  @PreDestroy
  fun shutdown() {
    keyStoreScheduler.shutdown()
    keyRotatorScheduler.shutdown()
    logger.info("Sleeping for 10 seconds to allow leftovers to be written")
    try {
      Thread.sleep(10000)
    } catch (ignored: InterruptedException) {

    }
  }

  abstract fun mapToIndexRequests(metric: GrapheneMetric?) : List<IndexRequest>

  abstract fun createTemplateIfNotExists(): PutIndexTemplateRequest

  abstract fun createIndexIfNotExists(index: String)

}
