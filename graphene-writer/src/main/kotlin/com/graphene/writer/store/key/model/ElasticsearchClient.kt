package com.graphene.writer.store.key.model

import org.elasticsearch.action.admin.indices.get.GetIndexResponse
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.action.get.MultiGetResponse
import org.elasticsearch.client.RequestOptions
import java.io.Closeable

interface ElasticsearchClient : Closeable {

  fun createIndexIfNotExists(index: String)

  fun createTemplateIfNotExists(templatePattern: String, templateName: String, templateSource: String)

  fun getCurrentIndex(index: String, tenant: String): String

  fun getLatestIndex(index: String): String

  fun bulk(index: String, type: String, tenant: String, grapheneIndexRequests: List<GrapheneIndexRequest>, default: RequestOptions): BulkResponse

  fun mget(multiGetRequest: MultiGetRequest, default: RequestOptions): MultiGetResponse

  fun getIndices(): GetIndexResponse

  fun addAlias(latestIndex: String, currentPointer: String, dateAlias: String)

  fun existsAlias(index: String, currentAlias: String): Boolean

}
