import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

public class Server {
    static int TIME_OUT = 2000;//milliseconds

    public static void main(String[] args) throws Exception {
        int id = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        Gson gson = new Gson();
        Config config = gson.fromJson(new JsonReader(new FileReader("config.txt")), Config.class);
        Server server = new Server(id, config);
        server.run();
    }

    private class VoteResult {
        int count;
        int value;

        VoteResult(int c, int v) {
            count = c;
            value = v;
        }
    }

    private int id;
    private HashMap<String, Integer> fileVersionMap;
    private HashMap<String, Queue<Message>> clientRequestQueue;
    private HashMap<String, VoteResult> voteResultMap;//key : filename_from_version;
    ExecutorService pool;
    ServerSocket listener;
    //each file has a waiting list, a server can have at most one request for each file
    //ConcurrentHashMap<filename, ConcurrentHashMap<fromPort, Socket>>
    private ConcurrentHashMap<String, ConcurrentHashMap<Integer, Socket>> replyWaitList;
    private Map<Integer, Socket> otherServers;//key: server id
    private ConcurrentHashMap<Integer, Socket> clients;//key: client port
    private ConcurrentHashMap<Integer, Integer> clientId2Port;//key: client id, value: client port
    private Config config;

    Server(int id, Config config) throws IOException {
        //create empty folder
        File folder = new File(String.valueOf(id));
        if (!folder.exists()) {
            folder.mkdir();
        } else {
            for (String filename : folder.list()) {
                (new File(folder.getPath(), filename)).delete();
            }
        }
        this.config = config;
        this.id = id;
        clientRequestQueue = new HashMap<>();
        voteResultMap = new HashMap<>();
        fileVersionMap = new HashMap<>();
        clients = new ConcurrentHashMap<>();
        clientId2Port = new ConcurrentHashMap<>();
        pool = Executors.newFixedThreadPool(20);
        listener = new ServerSocket(config.servers.get(id).port);
        //loop until connections with all the other servers are established
        this.otherServers = new HashMap<>();
        DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " starts");
        Collection<Config.ServerConfig> serverConfigs = config.servers.values();
        while (otherServers.size() < config.servers.size() - 1) {
            for (Config.ServerConfig sc : serverConfigs) {
                if (sc.id != id && !otherServers.containsKey(sc.id)) {
                    try {
                        Socket socket = new Socket(sc.ip, sc.port);
                        socket.setSoTimeout(TIME_OUT);//set read time out
                        otherServers.put(sc.id, socket);
                        DebugHelper.Log(DebugHelper.Level.INFO, "Connect to Server:" + socket);
                    } catch (Exception e) {
                        //waiting for the other servers
                        DebugHelper.Log(DebugHelper.Level.DEBUG, "waiting for connection:" + sc.ip + ":" + sc.port);
                    }
                }
            }
        }
        DebugHelper.Log(DebugHelper.Level.INFO, "Initialized");
    }

    private void run() throws IOException {
        while (true) {
            pool.execute(new ServerListener(this, this.listener.accept()));
        }
    }

    //restore client socket for replying
    private void onClientConnected(Socket socket) {
        this.clients.put(socket.getPort(), socket);
    }

    //remove client socket when disconnected
    private void onClientDisconnected(Socket socket) {
        this.clients.remove(socket.getPort());
    }

    //if msg is sending from myself
    private boolean isLocalMsg(Message msg) {
        return this.id == msg.from;
    }

    //send message in json
    private void send(Message msg, Socket socket) throws IOException {
        msg.from = this.id;
        //log
        DebugHelper.Log(DebugHelper.Level.DEBUG, "Send:" + socket + msg.toString());
        try {
            if (Message.isS2SMsg(msg.type) && config.servers.get(id).group != config.servers.get(msg.to).group)//if servers are in different networks, throw SocketTimeoutException manually to simulate time out
                throw new SocketTimeoutException();
            else {//send msg
                socket.getOutputStream().write((msg.toString() + "\n").getBytes());
                socket.getOutputStream().flush();
                if (!Message.isS2SMsg(msg.type))
                    DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " sends a ack to client " + msg.to + " with " + msg.filename + "\'s content: " + msg.content);
                else
                    DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " sends a " + msg.type.toString() + " to server " + msg.to + " for appending \"" + msg.content + "\" in " + msg.filename + (msg.type == Message.Type.VOTE_ACK ? " vote:" + msg.votes : ""));
            }
        } catch (IOException e) {
            if (msg.type == Message.Type.VOTE_REQ) {//vote 0 if timeout
                DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " sends a " + msg.type.toString() + " to server " + msg.to + " for appending \"" + msg.content + "\" in " + msg.filename + "[Timeout]");
                Message ack = msg.clone();
                ack.from = ack.to;
                ack.to = id;
                ack.type = Message.Type.VOTE_ACK;
                ack.votes = 0;
                handleVoteAcknowledge(ack);
            }
        }
    }

    //broadcast to all the other servers
    private void broadcastToOtherServers(Message msg) throws IOException {
        for (int serverId : Utils.getReplicas(msg.filename)) {
            if (serverId == id) continue;
            msg.from = this.id;
            msg.to = serverId;
            send(msg, otherServers.get(serverId));
        }
    }

    private void handleClientRequest(Message msg, Socket socket) throws IOException {
        DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " receives a " + msg.type.toString() + " from client " + msg.from + " for appending \"" + msg.content + "\" in " + msg.filename);
        clientId2Port.put(msg.from, socket.getPort());//update client id->port map
        if (!clientRequestQueue.containsKey(msg.filename))
            clientRequestQueue.put(msg.filename, new LinkedList<>());
        clientRequestQueue.get(msg.filename).add(msg);
        if (msg.type == Message.Type.WRITE)
            requestVotes(msg);
        else
            tryProcessClientMessage(msg);
    }

    //handle vote request from other servers
    private void handleVoteRequest(Message msg) throws IOException {
        msg.type = Message.Type.VOTE_ACK;
        msg.votes = msg.from < id ? -1 : 1;
        msg.to = msg.from;
        msg.from = id;
        send(msg, otherServers.get(msg.to));
    }

    //handle vote acknowledge from other servers
    private void handleVoteAcknowledge(Message msg) throws IOException {
        String name = msg.getUniName();
        if (!voteResultMap.containsKey(name))
            return;//result has been decided before
        VoteResult votes = voteResultMap.get(name);
        votes.count++;
        votes.value += msg.votes;
        if (votes.count == 3) {
            tryProcessClientMessage(msg);
        }
    }

    private void tryProcessClientMessage(Message msg) throws IOException {
        Queue<Message> q = clientRequestQueue.get(msg.filename);
        while (q != null && !q.isEmpty()) {
            Message head = q.peek();
            if (head.type == Message.Type.READ) {
                handleReadRequest(q.poll());
            } else if (head.filename.equals(msg.filename) && head.from == msg.clientId && head.version == msg.version) {
                q.poll();
                String voteUniName = head.getUniName();
                VoteResult votes = voteResultMap.get(voteUniName);
                if (votes.value >= 2) {//passed with majority
                    appendFile(msg.filename, msg.content);
                    fileVersionMap.put(msg.filename, fileVersionMap.getOrDefault(msg.filename, 0) + 1);
                    //broadcast commit
                    Message commit = msg.clone();
                    commit.type = Message.Type.COMMIT;
                    broadcastToOtherServers(commit);
                }
                voteResultMap.remove(voteUniName);
            } else
                break;
        }
    }

    private void handleReadRequest(Message msg) throws IOException {
        Message response = msg.clone();
        response.type = Message.Type.RESPONSE;
        try {
            response.content = readFile(msg.filename);
        } catch (IOException e) {
            response.result = Message.Result.FAIL;
        } finally {
            send(response, clients.get(clientId2Port.get(msg.from)));
        }
    }

    //handle commit message from master server
    private void handleCommitMsg(Message msg) throws IOException {
        DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " receives a " + msg.type.toString() + " from server " + msg.from + " for appending \"" + msg.content + "\" in " + msg.filename);
        appendFile(msg.filename, msg.content);
        fileVersionMap.put(msg.filename, fileVersionMap.getOrDefault(msg.filename, 0) + 1);
    }

    private void requestVotes(Message msg) throws IOException {
        voteResultMap.put(msg.getUniName(), new VoteResult(1, 1));//vote for myself
        Message vote = msg.clone();
        vote.type = Message.Type.VOTE_REQ;
        vote.clientId = msg.from;
        broadcastToOtherServers(vote);
    }

    //append local file
    private void appendFile(String filename, String content) throws IOException {
        FileWriter writer = new FileWriter(id + "/" + filename, true);
        writer.append(content);
        writer.close();
    }

    private String readFile(String filename) throws IOException {
        File file = new File(id + "/" + filename);
        BufferedReader reader = new BufferedReader(new FileReader(file));
        char[] buff = new char[(int) file.length()];
        reader.read(buff);
        reader.close();
        return new String(buff);
    }

    //server listener thread
    private static class ServerListener implements Runnable {
        Server server;
        Socket socket;
        Gson gson;

        ServerListener(Server server, Socket socket) {
            this.server = server;
            this.socket = socket;
        }

        private void processMsg(Message msg) throws IOException {
            DebugHelper.Log(DebugHelper.Level.DEBUG, "Receive:" + socket + msg.toString());
            switch (msg.type) {
                case READ:
                case WRITE:
                    this.server.handleClientRequest(msg, socket);
                    break;
                case VOTE_REQ:
                    this.server.handleVoteRequest(msg);
                    break;
                case VOTE_ACK:
                    this.server.handleVoteAcknowledge(msg);
                    break;
                case COMMIT:
                    this.server.handleCommitMsg(msg);
                    break;
            }
        }

        @Override
        public void run() {
            DebugHelper.Log(DebugHelper.Level.DEBUG, "Connected: " + this.socket);
            try {
                this.server.onClientConnected(this.socket);
                Scanner in = new Scanner(this.socket.getInputStream());
                gson = new Gson();
                while (in.hasNextLine()) {
                    Message msg = gson.fromJson(in.nextLine(), Message.class);
                    synchronized (this.server) {
                        processMsg(msg);
                    }
                }
            } catch (Exception e) {
                DebugHelper.Log(DebugHelper.Level.DEBUG, "ServerListener Exception " + e.getMessage() + ":" + this.socket);
                e.printStackTrace();
            } finally {
                try {
                    this.server.onClientDisconnected(this.socket);
                    this.socket.close();
                } catch (IOException e) {
                    DebugHelper.Log(DebugHelper.Level.DEBUG, "Socket Close Exception " + e.getMessage() + ":" + this.socket);
                }
                DebugHelper.Log(DebugHelper.Level.DEBUG, "Closed: " + this.socket);
            }
        }
    }
}
