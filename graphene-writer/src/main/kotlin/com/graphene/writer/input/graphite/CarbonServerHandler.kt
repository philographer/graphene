package com.graphene.writer.input.graphite

import com.google.common.base.CharMatcher
import com.graphene.writer.domain.Metric
import com.graphene.writer.input.graphite.property.InputGraphiteCarbonProperty
import com.graphene.writer.processor.GrapheneProcessor
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.CharsetUtil
import java.util.Objects
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import org.apache.logging.log4j.LogManager
import org.jmxtrans.embedded.QueryResult
import org.jmxtrans.embedded.output.GraphiteWriter
import org.springframework.stereotype.Component

/**
 * @author Andrei Ivanov
 * @author jerome89
 */
@Component
@ChannelHandler.Sharable
class CarbonServerHandler(
  private val inputGraphiteCarbonProperty: InputGraphiteCarbonProperty,
  private val grapheneProcessor: GrapheneProcessor
) : ChannelInboundHandlerAdapter() {

  private val logger = LogManager.getLogger(CarbonServerHandler::class.java)
  private lateinit var graphiteConverter: GraphiteMetricConverter
  private lateinit var graphiteWriter: GraphiteWriter

  @PostConstruct
  fun init() {
    this.graphiteConverter = GraphiteMetricConverter()
    if (Objects.nonNull(inputGraphiteCarbonProperty.route)) {
      this.graphiteWriter = GraphiteWriter()
      this.graphiteWriter.settings["host"] = inputGraphiteCarbonProperty.route!!.host
      this.graphiteWriter.settings["port"] = inputGraphiteCarbonProperty.route!!.port
      this.graphiteWriter.settings["namePrefix"] = IGNORE
      this.graphiteWriter.start()
    }
  }

  @Throws(Exception::class)
  override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
    val byteBuf = msg as ByteBuf

    try {
      val metric = Metric(byteBuf.toString(CharsetUtil.UTF_8).trim { it <= ' ' })
      if (CharMatcher.ascii().matchesAllOf(metric.tenant)) {
        val grapheneMetrics = graphiteConverter.convert(GraphiteMetric(metric.path, metric.value, metric.timestamp))
        for (grapheneMetric in grapheneMetrics) {
          grapheneProcessor.process(grapheneMetric)

          if (Objects.nonNull(inputGraphiteCarbonProperty.route)) {
            graphiteWriter.write(listOf(QueryResult(metric.path, metric.value, metric.timestamp * 1_000L)))
          }
        }
      } else {
        logger.warn("Non ASCII characters received, discarding: $metric")
      }
    } catch (e: Exception) {
      logger.error(e)
    }

    byteBuf.release()
  }

  @PreDestroy
  fun destroy() {
    if (Objects.nonNull(inputGraphiteCarbonProperty.route)) {
      graphiteWriter.stop()
    }
  }

  companion object {
    const val IGNORE = ""
  }
}
