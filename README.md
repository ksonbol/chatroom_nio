# chatroom_nio
A chatroom client/server application that utilizes Java NIO. This allows using one server thread to handle multiple client connections (for both sending and receivng messages), making it more efficient than the traditional thread per client approach of using Java IO. The application also applies Lamport's Timestamps just to demonstrate how they work.

# Features
- Server side: only two threads for ALL clients:
    - One for handling reading and writing to all clients (channels) whenever they are ready for read or write.
    - Another for keeping track of heartbeats of all clients, and removing clients which are not active (for more than 4T, where T is the heartbeat frequency).
- Client side: Three threads:
    - One for handling sending and receiving messages from server.
    - Another for accepting user input.
    - The last one for sending periodic heartbeats every T ms (200 ms is used).
    
 - Dealing with a non-blocking stream of data:
     - Sending messages: write bytes to write buffer, then copy data from write buffer to client/server channel.
     - Reading messages: read bytes from channel to read buffer, then read the bytes from read buffer and parse them.
     - Always send message size before actual message to be able to separate messages correctly when reading.
  
- Adding short thread sleep periods makes the application's usage of CPU very low, but may not be suitable for production.

# Usage
- Server application takes one optional argument: the port number to use, default is 4444.

- Client application takes two arguments: hostname/IP address and port number of server.

# Note
This is a simple implementation of a chatroom console application and may not be suitable for production.
