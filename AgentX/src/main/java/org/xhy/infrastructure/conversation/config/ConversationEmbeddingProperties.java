package org.xhy.infrastructure.conversation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties for conversation recall vector storage. */
@ConfigurationProperties(prefix = "conversation.embedding")
public class ConversationEmbeddingProperties {

    private VectorStore vectorStore = new VectorStore();

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public static class VectorStore {
        private String host;
        private int port = 5432;
        private String user;
        private String password;
        private String database;
        private String table = "public.conversation_vector_store";
        private int dimension = 1024;
        private boolean dropTableFirst = false;
        private boolean createTable = true;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public boolean isDropTableFirst() {
            return dropTableFirst;
        }

        public void setDropTableFirst(boolean dropTableFirst) {
            this.dropTableFirst = dropTableFirst;
        }

        public boolean isCreateTable() {
            return createTable;
        }

        public void setCreateTable(boolean createTable) {
            this.createTable = createTable;
        }
    }
}
