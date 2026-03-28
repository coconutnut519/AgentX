package org.xhy.infrastructure.conversation.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Conversation recall vector store configuration. */
@Configuration
@EnableConfigurationProperties(ConversationEmbeddingProperties.class)
public class ConversationEmbeddingConfig {

    private final ConversationEmbeddingProperties props;

    public ConversationEmbeddingConfig(ConversationEmbeddingProperties props) {
        this.props = props;
    }

    @Bean(name = "conversationEmbeddingStore")
    public EmbeddingStore<TextSegment> conversationEmbeddingStore() {
        ConversationEmbeddingProperties.VectorStore c = props.getVectorStore();
        return PgVectorEmbeddingStore.builder().table(c.getTable()).dropTableFirst(c.isDropTableFirst())
                .createTable(c.isCreateTable()).host(c.getHost()).port(c.getPort()).user(c.getUser())
                .password(c.getPassword()).dimension(c.getDimension()).database(c.getDatabase()).build();
    }
}
