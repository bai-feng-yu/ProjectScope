package agent_backend.persistence.jdbc;

final class JdbcStoreSupport {
    private JdbcStoreSupport() {}

    static void requireOne(int rows, String operation, String id) {
        if (rows != 1) {
            throw new IllegalStateException("Persistence transition rejected: " + operation + " [" + id + "]");
        }
    }
}
