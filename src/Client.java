import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;

public class Client {

    public static void main(String[] args) {
        try {
            int id = args.length == 0 ? 10 : Integer.parseInt(args[0]);
            Gson gson = new Gson();
            Config config = gson.fromJson(new JsonReader(new FileReader("config.txt")), Config.class);
            Client client = new Client(id, config);
            client.run();
            client.shutDown();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private int id;
    private Config config;
    private HashMap<String, Integer> fileVersionMap;
    private List<Socket> serverList;
    private HashMap<Socket, Scanner> scannerMap;
    private Gson gson;

    Client(int id, Config config) {
        this.id = id;
        this.config = config;
        serverList = new ArrayList<>();
        scannerMap = new HashMap<>();
        fileVersionMap = new HashMap<>();
        gson = new Gson();
        DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " starts");
        //connect to servers
        Collection<Config.ServerConfig> serverConfigs = config.servers.values();
        while (serverList.size() < config.servers.size() - 1) {
            for (Config.ServerConfig sc : serverConfigs) {
                try {
                    Socket socket = new Socket(sc.ip, sc.port);
                    serverList.add(socket);
                    scannerMap.put(socket, new Scanner(socket.getInputStream()));
                    DebugHelper.Log(DebugHelper.Level.INFO, "Connect to Server:" + socket);
                } catch (Exception e) {
                    DebugHelper.Log(DebugHelper.Level.DEBUG, "waiting for connection:" + sc.ip + ":" + sc.port);
                }
            }
        }
    }

    private void run() throws InterruptedException {
        for (Config.Command command : config.clients.get(id).commands) {
            for (int i = 0; i < command.loop; i++) {
                if (command.type == Message.Type.WRITE) {
                    write(command.filename, command.content);
                    Thread.sleep(command.sleepMillis);
                } else if (command.type == Message.Type.READ) {
                    read(command.filename);
                    Thread.sleep(command.sleepMillis);
                }
            }
        }
    }

    private int getVersion(String filename) {
        return fileVersionMap.getOrDefault(filename, 0);
    }

    private void read(String filename) {
        int[] arr = Utils.getReplicas(filename);
        int randServerId = (int) (Math.random() * arr.length);
        Message msg = new Message(Message.Type.READ, filename, id, randServerId);
        Socket socket = serverList.get(randServerId);
        if (send(msg, socket)) {
            Scanner scanner = scannerMap.get(socket);
            while (scanner.hasNextLine()) {
                processMsg(gson.fromJson(scanner.nextLine(), Message.class));
                break;
            }
        }
    }

    private void write(String filename, String content) {
        int[] arr = Utils.getReplicas(filename);
        DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " sends WRITE " + filename + " to servers:" + Arrays.toString(arr));
        for (int serverId : arr) {
            int v = getVersion(filename);
            Message msg = new Message(Message.Type.WRITE, filename, id, serverId, content, v);
            fileVersionMap.put(filename, v + 1);
            Socket socket = serverList.get(serverId);
            send(msg, socket);
        }
    }

    //send message in json
    private boolean send(Message msg, Socket socket) {
        msg.from = this.id;
        //log
        DebugHelper.Log(DebugHelper.Level.DEBUG, "Send:" + socket + msg.toString());
        //send msg
        try {
            if (config.clients.get(id).group != config.servers.get(msg.to).group)//if client and server are in different networks, throw SocketTimeoutException manually to simulate time out
                throw new SocketTimeoutException();
            else {
                socket.getOutputStream().write((msg.toString() + "\n").getBytes());
                socket.getOutputStream().flush();
                if (msg.type == Message.Type.WRITE)
                    DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " sends a " + msg.type.toString() + " to server " + msg.to + " for appending " + msg.content + " in " + msg.filename);
                else
                    DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " sends a " + msg.type.toString() + " to server " + msg.to + " for reading " + msg.filename);
            }
        } catch (IOException e) {
            DebugHelper.Log(DebugHelper.Level.INFO, "[Timeout]client " + id + " sends a " + msg.type.toString() + " message to server " + msg.to);
            return false;
        }
        return true;
    }

    private void processMsg(Message msg) {
        DebugHelper.Log(DebugHelper.Level.DEBUG, msg.toString());
        if (msg.result == Message.Result.FAIL)
            DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " fails to read " + msg.filename + " from server " + msg.from);
        else
            DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " receives: " + msg.content + " from server " + msg.from);
    }

    private void shutDown() throws IOException {
        for (Socket socket : serverList) {
            socket.shutdownOutput();
        }
        DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " gracefully shutdown.");
    }
}
