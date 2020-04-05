public class Utils {
    static int SERVER_COUNT = 7;
    static int REPLICA_COUNT = 3;

    static int hash(String input) {
        return input.hashCode() % SERVER_COUNT;
    }

    static int[] getReplicas(String filename) {
        int hashCode = hash(filename);
        int[] res = new int[REPLICA_COUNT];
        for (int i = 0; i < REPLICA_COUNT; i++)
            res[i] = (hashCode + i) % SERVER_COUNT;
        return res;
    }
}
