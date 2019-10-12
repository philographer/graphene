package com.graphene.writer.store.key

import com.graphene.writer.store.key.model.ElasticsearchFactory
import com.graphene.writer.store.key.model.IndexBasedKeyStoreHandlerProperty
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.common.xcontent.XContentFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 *
 * @author dark
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(prefix = "graphene.writer.store.key.handlers.index-based-key-store-handler", name = ["enabled"], havingValue = "true")
class IndexBasedKeyStoreHandler(
  elasticsearchFactory: ElasticsearchFactory,
  property: IndexBasedKeyStoreHandlerProperty
) : AbstractElasticsearchKeyStoreHandler(elasticsearchFactory, property) {

  override fun source(tenant: String, graphiteKeyPart: String, depth: Int, leaf: Boolean): XContentBuilder {
    return XContentFactory.jsonBuilder()
      .startObject()
      .endObject()
  }

}
