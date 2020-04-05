# Voting Protocol
## Description

Let there be seven data servers, S0, S1, . . . S6 and five clients, C0, C1, . . . , C4. There exist communication channels between all servers, and from each client to all the servers. All communication channels are FIFO and reliable when they are operational. Occasionally, a channel may be disrupted in which case no message can be communicated across that channel. There exists a hash function, H, such that for each object, O<sub>k</sub>, H(O<sub>k</sub>) yields a value in the range 0−6.

- When a client, Ci has to insert/update an object, O<sub>k</sub>, it performs the write at three servers numbered: H(O<sub>k</sub>), H(O<sub>k</sub>)+1 modulo 7, and H(O<sub>k</sub>)+2 modulo 7.

- When a client, Cj has to read an object, O<sub>k</sub>, it can read the value from any one of the three servers: H(O<sub>k</sub>), H(O<sub>k</sub>)+1 modulo 7, or H(O<sub>k</sub>)+2 modulo 7.
In this project you are required to implement the following:

1. A client should be able to randomly choose any of the three replicas of an object when it wishes to read the value of the object. If a client tries to read an object that is not present (has not been inserted earlier by any client), the operation should return with an error code.
2. When a client wishes to update/insert an object into the data repository, it should be able to successfully perform the operation on at least two, and if possible all the three servers that are required to store the object.
3. If a client is unable to access at least two out of the three servers that are required to store an object, then the client does not perform updates to any replica of that object.
4. If two or more clients try to concurrently write to the same object and at least two replicas are available, the writes must be performed in the same order at all the replicas of the object.

You will need to demonstrate that you have implemented all the requirements mentioned above. For instance, you may need to selectively disable some channel(s) for a brief period of time so that a client is only able to access two or one replica of an object. If at least two replicas of the object are accessible from a client, updates to that object should be allowed. However, if only one replica of the object is accessible from a client, the client should abort the update and output a corresponding message.

You must also selectively disable some channels so that the clients and servers get partitioned into two components, each with a subset of clients and servers such that some writes are permitted in one partition, while other updates are permitted in the other partition.

## Run

- put server.jar and config.txt on seven different servers, execute 
`java -jar server.jar [id]`
- put client.jar and config.txt on several different clients 
`java -jar client.jar [id]`

## Config File

```
{
    "servers": {
        "0": {
            "id": 0,
            "ip": "127.0.0.1",
            "port": 23120,
            "group": 1
        },
        "1": {
            "id": 1,
            "ip": "127.0.0.1",
            "port": 23121,
            "group": 1
        },
        "2": {
            "id": 2,
            "ip": "127.0.0.1",
            "port": 23122,
            "group": 2
        }
    },
    "clients": {
        "10": {
            "id": 10,
            "group": 1,
            "commands": [
                {
                    "filename": "1.txt",
                    "content": "a",
                    "type": "WRITE",
                    "sleepMillis": 50
                },
                {
                    "filename": "1.txt",
                    "type": "READ",
                    "sleepMillis": 50
                }
            ]
        }
    }
}
```
- servers: Object of {id: ServerConfig} pairs
    - ServerConfig
        - id
        - ip
        - port
        - group: servers with same group number are in the same network
        
- clients: Object of {id: ClientConfig} pairs
    - ClientConfig
        - id
        - group
        - commands: array of Command
            - Command
                - filename
                - content
                - type: "READ" or "WRITE"
                - sleepMillis: sleep after command execution

## Hash Function
Based on String.hashCode():

```
objectName.hashCode() % 7
```

## Voting Protocol

### Main Idea 

For each ClientWriteRequest, a server should send a VoteRequest to the other servers to get write permission. So there will be n(n-1) VoteRequests. After voting, there will be at most one candidate collects majority votes(The next paragraph will discuss how to achieve that), which is the master site of the three replicas. Only a master site can control the write operations in its network. The operations will execute in the order of messages arrivals at the master site, so all the replicas in the same network will be consistent.

### Detail

Let there be three servers, S0, S1, and S2. A client sends a write message to all of them to update an object.

1. When a server receives a write message from the client. It will place the request in ClientRequestQueue, send a VoteRequest to the other servers, and store a vote record with count 1 and value 1, represents the  

2. When a server receives a VoteRequest, it compares its id with the sender's id. If its id is smaller than the sender's, it will reply a VoteAcknowledge with a vote value of 1 as permission;  If its id is greater than the sender's, it will reply a VoteAcknowledge with a vote value of -1 as rejection.

3. When a server receives a VoteAcknowledge, it collects votes, increases vote count and sum vote value. If VoteRequest is timeout, take it as a give up, vote count still equals 1, but vote value is 0. When total vote count equals 3, check vote value. 
If value is equal to or greater than 2, the request passes with majority:

    - If the request is not head of the ClientRequestQueue, leave it to the next time when another vote result comes out. If it is the head of the ClientRequestQueue, remove it, update local file, and send Commit message to the others to update their files.
 
    Or, the request fails:

    - If the request is not head of the ClientRequestQueue, leave it to the next time when another vote result comes out.

### Examples

votes = {vote value from S0, vote value from S1, vote value from S2}

    how to determine vote value:
        - 0: VoteRequest timeout
        - 1: voter's id is smaller than the candidate's
        - -1: voter's id is greater than the candidate's

1. if S0, S1, S2 are in the same network:

    |id|votes|sum|
    | --- | --- | --- |
    |S0|{1, -1, -1}|-1|
    |S1|{1, 1, -1}|1|
    |S2|{1, 1, 1}|3|
    
2. if S0, S1 are in network1, S2 in network2:

    |id|votes|sum|
    | --- | --- | --- |
    |S0|{1, -1, 0}|0|
    |S1|{1, 1, 0}|2|
    |S2|{0, 0, 1}|1|
    
3. if S0, S1, S3 are in different networks:

    |id|votes|sum|
    | --- | --- | --- |
    |S0|{1, 0, 0}|1|
    |S1|{0, 1, 0}|1|
    |S2|{0, 0, 1}|1|
    
So there is at most one server can get vote value equals or greater than 2.

### Read

Based on the requirement, read should be sent to only one server, and the result could be out of date.

What if we want the most updated result? There is one possible solution:

A client sends ClientReadRequest to all the three servers like the ClientWriteRequest. Servers send VoteRequest with version# to the other servers. Vote value will be determined by comparing version#. Finally, the server with the latest version# will become the master site. The master site sends its object content to other sites to keep synchronized and responses to the client.

## Simulating Time-out

We'll only concern read timeout rather than connect timeout. Because for connect timeout, there should be some other network detection and reconnect strategies. Read timeout is the fault we are trying to tolerance with voting protocol in this project.

When a message is about to send, check whether the sender and receiver are in the same network by comparing the group# in config file. If they are not int the same network, then the sender throws a SocketTimeoutException manually to simulate a real timeout.


## Serialization & Deserialization
[Gson](https://github.com/google/gson)

## Result analysis

### test case
```
{
    "servers": {
        "0": {
            "id": 0,
            "ip": "127.0.0.1",
            "port": 23120,
            "group": 1
        },
        "1": {
            "id": 1,
            "ip": "127.0.0.1",
            "port": 23121,
            "group": 1
        },
        "2": {
            "id": 2,
            "ip": "127.0.0.1",
            "port": 23122,
            "group": 1
        },
        "3": {
            "id": 3,
            "ip": "127.0.0.1",
            "port": 23123,
            "group": 1
        },
        "4": {
            "id": 4,
            "ip": "127.0.0.1",
            "port": 23124,
            "group": 1
        },
        "5": {
            "id": 5,
            "ip": "127.0.0.1",
            "port": 23125,
            "group": 1
        },
        "6": {
            "id": 6,
            "ip": "127.0.0.1",
            "port": 23126,
            "group": 1
        }
    },
    "clients": {
        "10": {
            "id": 10,
            "group": 1,
            "commands": [
                {
                    "filename": "2.txt",
                    "content": "a from client 10\n",
                    "type": "WRITE",
                    "sleepMillis": 20,
                    "loop": 100 
                },
                {
                    "filename": "4.txt",
                    "content": "b from client 10\n",
                    "type": "WRITE",
                    "sleepMillis": 0,
                    "loop": 100
                },
                {
                    "filename": "6.txt",
                    "content": "c from client 10\n",
                    "type": "WRITE",
                    "sleepMillis": 0,
                    "loop": 100
                },
                {
                    "filename": "4.txt",
                    "type": "READ",
                    "sleepMillis": 0,
                    "loop": 1
                }
            ]
        },
        "11": {
            "id": 11,
            "group": 1,
            "commands": [
                {
                    "filename": "2.txt",
                    "content": "D from client 11\n",
                    "type": "WRITE",
                    "sleepMillis": 0,
                    "loop": 100 
                },
                {
                    "filename": "4.txt",
                    "content": "E from client 11\n",
                    "type": "WRITE",
                    "sleepMillis": 0,
                    "loop": 100
                },
                {
                    "filename": "6.txt",
                    "content": "F from client 11\n",
                    "type": "WRITE",
                    "sleepMillis": 0,
                    "loop": 100
                },
                {
                    "filename": "2.txt",
                    "type": "READ",
                    "sleepMillis": 0,
                    "loop": 1
                }
            ]
        }
    }
}
```
#### analysis

All the clients and servers are in the same network. Using HashFunction we can get replica sites for each object:

|object name|sites|
| --- | --- |
|0.txt|S0, S1, S2|
|2.txt|S1, S2, S3|
|4.txt|S2, S3, S4|
|6.txt|S3, S4, S5|
|8.txt|S4, S5, S6|

|group #|sites|
| --- | --- |
|1|S0, S2, S5, S6, C10, C11, C12|
|2|S3, S4, C13|
|3|S1, C14|

So C10, C11, C12 will successfully write 0.txt and 8.txt; C13 will successfully write 4.txt and 6.txt; C14 will fail to write any files;

#### result
|site|objects|
| --- | --- |
|S0|0.txt|
|S1||
|S2|0.txt|
|S3|4.txt, 6.txt|
|S4|4.txt, 6.txt|
|S5|8.txt|
|S6|8.txt|
All the files with the same name are identical. Result and analysis are consistent.