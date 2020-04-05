import java.util.Map;

public class Config {
    public class ServerConfig {
        int id;
        String ip;
        int port;
        int group;
    }

    public class Command {
        String filename;
        String content;
        Message.Type type;
        int sleepMillis = 0;
        int loop = 1;
    }

    public class ClientConfig {
        int id;
        int group;
        Command[] commands;
    }

    Map<Integer, ServerConfig> servers;
    Map<Integer, ClientConfig> clients;
}
