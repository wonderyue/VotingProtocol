import com.google.gson.Gson;

public class Message {
    public enum Type {
        //server-server message
        VOTE_REQ,
        VOTE_ACK,
        COMMIT,
        //client-server message
        WRITE,
        READ,
        //server-client message
        RESPONSE
    }

    public enum Result {
        SUCCESS,
        FAIL
    }

    static boolean isS2SMsg(Type type) {
        return type.compareTo(Type.COMMIT) <= 0;
    }

    Type type;
    String filename;
    int from;
    int to;
    String content;
    int version;
    int votes;
    int clientId;
    Result result = Result.SUCCESS;

    Message(Type type, String filename, int from, int to) {
        this.type = type;
        this.filename = filename;
        this.from = from;
        this.to = to;
    }

    Message(Type type, String filename, int from, int to, String content, int version) {
        this.type = type;
        this.filename = filename;
        this.from = from;
        this.to = to;
        this.content = content;
        this.version = version;
    }

    Message(Message.Type type, String filename, int from, int to, String content, int version, int votes, int clientId) {
        this.type = type;
        this.filename = filename;
        this.from = from;
        this.to = to;
        this.content = content;
        this.version = version;
        this.votes = votes;
        this.clientId = clientId;
    }

    protected static Gson gson;

    @Override
    public String toString() {
        if (gson == null)
            gson = new Gson();
        return gson.toJson(this);
    }

    public String getUniName() {
        if (this.type == Type.COMMIT || this.type == Type.VOTE_REQ || this.type == Type.VOTE_ACK)
            return this.filename + "_" + this.clientId + "_" + this.version;
        return this.filename + "_" + this.from + "_" + this.version;
    }

    @Override
    public Message clone() {
        return new Message(this.type, this.filename, this.from, this.to, this.content, this.version, this.votes, this.clientId);
    }
}

