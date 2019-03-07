package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the state of the server: active connected clients and a local counter.
 * 
 * @author Karim Sonbol
 *
 */
public class Server {
	/**
	 * Keeps the list of all connections as a map of web address strings to to Client objects
	 */	
	protected ConcurrentHashMap<String,Client> connections;
	
	/**/
	private HashSet<String> usernames;
	private Selector selector;
	private HeartBeatManager heartBeatManager;
	private volatile HashMap<String,ArrayDeque<String>> toWrite;

	private final int bufCapacity = 2048;
	private final String usernameReq = "Choose a unique username to enter the chat room: ";
	private final String welcomeMsg = ""
			+ "**************************************************\n"
			+ "* Welcome to the chat room! To exit type ':quit' *\n"
			+ "**************************************************";
	
	/**
	 * Keeps a local counter for using Lamport's timestamp, shared between all server threads.
	 */
	protected volatile long counter;
	
	private int port;
	
	private ByteBuffer readBuf;
	
	private ByteBuffer writeBuf;
	
	private final long MAXCOUNTER = 1000000;
	
	/**
	 * Constructs a new {@link Server} object.
	 * Initializes the connections HashMap and the counter with a random value between [1,100]
	 */
	public Server(int port) {
		this.port = port;		
		connections = new ConcurrentHashMap<>();
		usernames = new HashSet<String>();
		heartBeatManager = new HeartBeatManager(this);
		// TODO: how to choose correct buffer size?
		readBuf = ByteBuffer.allocate(bufCapacity);
		writeBuf = ByteBuffer.allocate(bufCapacity);
		toWrite = new HashMap<>();

		Random rand = new Random();
		counter = (long) rand.nextInt(100) + 1;
		System.out.println("Initial counter: " + counter);
	}
	
	public void start() throws IOException {
		
		selector = Selector.open();
		ServerSocketChannel serverSocket = ServerSocketChannel.open();
		serverSocket.bind(new InetSocketAddress(port));
		serverSocket.configureBlocking(false);
		serverSocket.register(selector, SelectionKey.OP_ACCEPT);
		System.out.println("Listening on port " + port + "\n");
		heartBeatManager.start();
		
		while(true) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				
			}
			selector.select();
			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator<SelectionKey> iter = selectedKeys.iterator();
			
			while(iter.hasNext()) {
				SelectionKey key = iter.next();
				
				try {
					if (key.isAcceptable())
						acceptConnection(selector, serverSocket);
					
					if (key.isWritable())
						send(key);
					
					if (key.isReadable())
						receiveMessage(key);
				} catch(CancelledKeyException e) {
					
				} finally {
				iter.remove();
				}
			}
		}
	}
	
	private void acceptConnection(Selector selector, ServerSocketChannel serverSocket) {
		try {
			SocketChannel socket = serverSocket.accept();
			socket.configureBlocking(false);
			socket.register(selector, (SelectionKey.OP_READ | SelectionKey.OP_WRITE));
			String address = socket.getRemoteAddress().toString();
			Client client = new Client(address, socket);
			addConnection(client);
			heartBeatManager.initializeHeartBeat(client);
			sendMessage(usernameReq, client);
			System.out.println("Accepted connection from: "+ address);
		} catch (IOException e) {
			// client failed suddenly?
		}
	}
	
	private Client getClient(SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		String remoteAddress = "";
		try {
			remoteAddress = channel.getRemoteAddress().toString();
		} catch (IOException e) {
			// client connection failed
			return null;
		}
		return connections.get(remoteAddress);
	}
	
	private void receiveMessage(SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		Client client = getClient(key);
		if (client == null) {
			// client connection is terminated
			return;
		}
		int bytesRead = read(channel);
		if (bytesRead == 0 || bytesRead == -1) {
			// no new messages or connection may be closed
			return;
		}
		readBuf.flip(); // switch buffer from writing mode to reading mode
		while (readBuf.remaining() > 0) {
			long ts = readBuf.getLong();
			counter = Math.max(counter, ts) + 1;
			int msgLength = readBuf.getInt();
			if (msgLength == 0) {
				// heart beat message
				heartBeatManager.addHeartBeat(client);
			} else { // normal message
				System.out.println("Message received from " + client.getAddress());
				System.out.printf("Sent at: %d, received at: %d\n\n", ts, counter);
				byte[] bytes = Arrays.copyOfRange(readBuf.array(), readBuf.position(), 
						readBuf.position() + msgLength); // get text part as bytes
				String message = new String(bytes, StandardCharsets.UTF_8);
				if (client.hasUsername()) {
					readMessage(message, client);
				} else {
					checkUsername(message, client);
				}
			}
			if ((readBuf.position() + msgLength) > readBuf.limit())
				readBuf.position(readBuf.limit()); // or break
			else
				readBuf.position(readBuf.position() + msgLength);
		}
		readBuf.clear(); // make buffer ready for next channel read operation
	}
	
	
	private int read(SocketChannel client) {
		try {
			return client.read(readBuf);
		} catch (IOException e) {
			// connection was probably closed
			return -1;
		}
	}
	
	private void addConnection(Client client) {
		connections.put(client.getAddress(), client);
	}
	
	private void sendMessage(String message, Client client) {
		String addr = client.getAddress();
		if (!toWrite.containsKey(addr))
			toWrite.put(addr, new ArrayDeque<String>());
		ArrayDeque<String> msgs = toWrite.get(addr);
		msgs.add(message);
	}
	
		private void send(SelectionKey key) {
			Client client = getClient(key);
			if (client == null) {
				// client connection is terminated
				return;
			}
			String addr = client.getAddress();
			if (!toWrite.containsKey(addr) || toWrite.get(addr).isEmpty())
				return;
			String message = "";
				message = toWrite.get(addr).remove();
			if (++counter >= MAXCOUNTER)
				counter = 1;
			long ts = counter;
			byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
			
			try {
				writeBuf.putLong(ts);
				writeBuf.putInt(msgBytes.length); // size of message (in bytes) is written first
				writeBuf.put(msgBytes); // buffer: [ts message]
				writeBuf.flip(); // prepare buffer for channel write
				SocketChannel channel = client.getChannel();
				channel.write(writeBuf);
				System.out.printf("Message sent at: %d to %s\n\n", ts, client.getAddress());
				writeBuf.clear();
			} catch(IOException e) {
				writeBuf.clear();
				close(client);
			}
		}
	
	private void checkUsername(String username, Client client) {
		if (usernameExists(username)) {
			sendMessage("Sorry, username exists!\n" + usernameReq, client);
		} else if (username.length() < 2) {
			sendMessage("Sorry, username must be at least two characters long!\n" + 
					usernameReq, client);
		} else {
			client.updateUsername(username);
			usernames.add(username);
			sendMessage(welcomeMsg, client);
			broadcast(username + " has joined the chat!", null, true);
		}
	}
	
	/**
	 * Check username against usernames of active clients
	 * 
	 * @param username chosen by user
	 * @return true if username is not unique among active clients, false otherwise
	 */
	public boolean usernameExists(String username) {
		return usernames.contains(username);
	}
	
	private void readMessage(String message, Client client) {
		if (message.trim().equals(":quit")) {
			sendMessage("-1", client); // send acknowledgement?
			heartBeatManager.closeClient(client);
		} else {
			broadcast(message, client, false);
		}
	}
		
	protected void broadcast(String message, Client sender, boolean isServerMsg) {
		final String toSend;
		if (isServerMsg) {
			toSend = message;
		} else {
			String senderUsername = sender.getUsername();
			toSend = senderUsername + ": " + message;
		}
		connections.forEach((addr, client) -> {
			if (client.inChatRoom()) {
				sendMessage(toSend, client);
			}
		});
	}
	
	public void close(Client client) {
		SocketChannel channel = client.getChannel();
		String addr = client.getAddress();
		try {
			System.out.println("Closed connection with " + addr + "\n");
			channel.close();
		} catch (IOException e) {
			// if the channel is already closed, manually cancel its SelectionKey, not sure if this is needed
			channel.keyFor(selector).cancel();
		}
		if (client.hasUsername())
			usernames.remove(client.getUsername());
		connections.remove(client.getAddress());
		client.setClosed();
	}
}


class Client {
	String username;
	String address;
	SocketChannel channel;
	long lastHeartBeat;
	boolean closed = false;
	
	public Client(String address, SocketChannel channel) {
		this.address = address;
		this.channel = channel;
		this.username = null;
	}
	
	public void updateUsername(String username) {
		this.username = username;
	}
	
	public String getUsername() {
		return username;
	}
	
	public String getAddress() {
		return address;
	}
	
	public SocketChannel getChannel() {
		return channel;
	}
	
	public long getLastHeartBeat() {
		return lastHeartBeat;
	}
	
	public void setLastHeartBeat(long time) {
		lastHeartBeat = time;
	}
	
	public boolean hasUsername() {
		return username != null;
	}
	
	public boolean inChatRoom() {
		return hasUsername();
	}
	
	public boolean isClosed() {
		return closed;
	}
	
	public void setClosed() {
		closed = true;
	}
}


class HeartBeatManager implements Runnable {
	
	private Server server;
	private int hbFactor = 4;
	private long hbPeriod = 200;
	
	public HeartBeatManager(Server server) {
		this.server = server;
	}
	
	public void start() {
		Thread th = new Thread(this);
		th.start();
	}
	
	public void run() {
		long now, last;
		while (true) {
			now = new Date().getTime(); // in milliseconds
			for (Client client: server.connections.values()) {
				last = client.getLastHeartBeat();
				if ((now - last) > (hbFactor * hbPeriod)) {
					closeClient(client);
				}
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
		}
	}
	
	public void initializeHeartBeat(Client client) {
		if (client.isClosed())
			return;
		long now = new Date().getTime();
		client.setLastHeartBeat(now);
	}
	
	public void addHeartBeat(Client client) {
		if (client.isClosed())
			return; // ignore if it is a message from a closed client
		long now = new Date().getTime(); // in milliseconds
		client.setLastHeartBeat(now);
	}
	
	public void closeClient(Client client) {
		server.close(client);
		if (client.getUsername() != null)
			server.broadcast(client.getUsername() + " has left the chat!", null, true);
	}
}