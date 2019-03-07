package client;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;

/**
 * Stores state about this client and connection to server.
 * Responsible for listening to server messages and for starting
 * threads for reading user input and sending heartbeats.
 * @author Karim Sonbol
 */
public class Client {
	
	protected final int bufCapacity = 2048;

	private InetSocketAddress address;
	
	/**
	 * socket that will be opened with server
	 */
	protected SocketChannel socket;
	
	/**
	 * Handler for listening to input from user.
	 */
	protected MessageSender writer;
	
	private SendHeartBeat hbHandler;
	
	private ByteBuffer writeBuf;
	
	private boolean stop = false;
	
	protected final long MAXCOUNTER = 1000000;

	/**
	 * Counter used for using Lamport's timestamps. This variable is shared among all client threads.
	 */
	protected volatile long counter;
	
	private ByteBuffer readBuf;
	
	private volatile ArrayDeque<String> toWrite;
	
	/**
	 * Initializes the Client object, setting ip address, port number  and initial (randomized) counter value
	 * @param ipaddr IP address of server to connect to.
	 * @param port Port number to connect to.
	 */
	public Client(InetAddress ipaddr, int port) {
		address = new InetSocketAddress(ipaddr, port);
		readBuf = ByteBuffer.allocate(bufCapacity);
		writeBuf = ByteBuffer.allocate(bufCapacity);
		toWrite = new ArrayDeque<>();
		Random rand = new Random();
		counter = rand.nextInt(100) + 1; // range between [1,100]
		System.out.println("Initial counter: " + counter + "\n");
	}
	
	/**
	 * Starts the client by connecting to server and starting two threads.
	 * Specifically, it follows these steps:<br>
	 * 1. Connect to the server using ipaddr and portnum<br>
	 * 2. Start a thread to listen to messages from user<br>
	 * 3. Start a thread for sending heartbeats to server.<br>
	 * 4. Reads messages from or send messages to server when channels are ready
	 */
	public void start() {
		try {
			socket = SocketChannel.open(address);
		}
		catch (IOException e) {
			System.out.println(e.getMessage());
			System.out.println("Check the IP address and port number you used.");
			System.exit(0);
		}
		
		writer = new MessageSender(this); // Thread for sending messages to server
		writer.start();
		
		hbHandler = new SendHeartBeat(this);
		hbHandler.start();
		
		// create selector to watch the socket
		try {
			Selector selector = Selector.open();
			socket.configureBlocking(false);
			socket.register(selector, (SelectionKey.OP_READ | SelectionKey.OP_WRITE));
			while (true) {
				try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
			}
				selector.select();
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> iter = selectedKeys.iterator();
				
				while(iter.hasNext()) {
					SelectionKey key = iter.next();
					
					try {
						if (key.isWritable()) {
							if (!toWrite.isEmpty())
								send();
						}
						
						if (key.isReadable()) {
							int bytesRead = read();
							if (bytesRead == -1) {
								key.cancel();
								stop();
							}
							receiveMessage();
						}
					} catch(CancelledKeyException e) {
						stop();
					} finally {
						iter.remove();
					}
				}
			}
		} catch (IOException e) {
			stop();
		}
	}
	
	private void receiveMessage() {
		readBuf.flip(); // switch buffer from writing mode to reading mode
		while (readBuf.remaining() > 0) {
			long ts = readBuf.getLong();
			counter = Math.max(counter, ts) + 1;
			int msgLength = readBuf.getInt(); // message length (in bytes)
			byte[] bytes = Arrays.copyOfRange(readBuf.array(), readBuf.position(), 
					readBuf.position() + msgLength); // get text part as bytes
			String msg = new String(bytes);
			System.out.printf("Sent at: %d, received at: %d\n", ts, counter);
			System.out.println(msg + "\n"); 
			if ((readBuf.position() + msgLength) > readBuf.limit())
				readBuf.position(readBuf.limit()); // or break;
			else
				readBuf.position(readBuf.position() + msgLength);
		}
		readBuf.clear(); // make buffer ready for next channel read operation
	}
	
	private int read() {
		try {
			return socket.read(readBuf);
		} catch (IOException e) {
			return -1;
		}
	}
	
	public boolean isStopped() {
		return this.stop;
	}
	
	public void stop() {
		stop = true;
		System.out.println("Connection terminated!");
		System.exit(0);
	}
	
	protected void sendMessage(String message) {
		toWrite.add(message);
	}
	
	private void send() {			
		if (++counter >= MAXCOUNTER)
			counter = 1;
		long ts = counter;
		String message = toWrite.remove();
		writeBuf.putLong(ts);
		byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
		writeBuf.putInt(msgBytes.length); // size of message (in bytes) is written first 
		writeBuf.put(msgBytes); // buffer: [ts message]
		writeBuf.flip(); // prepare buffer for channel write
		try {
			socket.write(writeBuf);
			writeBuf.clear();
			System.out.printf("Message sent to server at: %d\n\n", ts);
		} catch(IOException e) {
			writeBuf.clear();
			stop();
		}
	}
}

/**
 * A runnable that listens to user's input
 */
class MessageSender implements Runnable {
	/** 
	 * Client object, used for accessing client methods
	 */
	Client client;
	
	/**
	 * Scanner object used for reading input from user
	 */
	Scanner sc;
	
	/**
	 * Thread that will be created for listening to client messages
	 */
	Thread t;
	
//	ByteBuffer writeBuf;
	
	/** 
	 * Constructs a new MessageSender instance, setting the Client object.
	 * @param client object of Client class, used for accessing client methods.
	 */
	public MessageSender(Client client) {
		this.client = client;
		sc = new Scanner(System.in);
	}
	
	/**
	 * Creates a new Thread and starts it
	 */
	public void start() {
		t = new Thread(this);
		t.start();
	}
	
	@Override
	public void run() {
		String message;
		while (!client.isStopped()) {
			message = sc.nextLine();
			message = message.trim();
			if (!message.isEmpty())
				client.sendMessage(message);
		}
	}
}

class SendHeartBeat implements Runnable {
	Client client;
	Thread th;
	SocketChannel socket;
	ByteBuffer heartBeatBuf;
	long sleepPeriod = 200; // milliseconds
	
	private ArrayDeque<String> heartbeats;
	
	public SendHeartBeat(Client client) {
		this.client = client;
		this.socket = client.socket;
		heartBeatBuf = ByteBuffer.allocate(client.bufCapacity);
		heartbeats = new ArrayDeque<String>();
	}
	
	public void start() {
		th = new Thread(this);
		th.start();
	}
	
	public void run() {
		Selector selector = null;
		try {
			selector = Selector.open();
			socket.configureBlocking(false);
			socket.register(selector, SelectionKey.OP_WRITE);
		} catch(IOException e) {
			client.stop();
		}
		
		while (!client.isStopped()) {
			try {
				Thread.sleep(sleepPeriod);
			} catch (InterruptedException e) {
			}	
			addHeartBeat();
			try {
				selector.select();
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> iter = selectedKeys.iterator();
			
				while(iter.hasNext()) {
					SelectionKey key = iter.next();
						
					try {
						if (key.isWritable() && !heartbeats.isEmpty()) {
							sendHeartBeat();
						}
						iter.remove();
					} catch(CancelledKeyException e) {
						iter.remove();
						client.stop();
					}
				}
			} catch (IOException e) {
				client.stop();
			}
		}
	}
	
	protected void addHeartBeat() {
		heartbeats.add("");
	}
	
	private void sendHeartBeat() {
		if (++client.counter >= client.MAXCOUNTER)
			client.counter = 1;
		long ts = client.counter;
		heartbeats.remove();
		heartBeatBuf.putLong(ts);
		heartBeatBuf.putInt(0); // length of message is 0 for heartbeats
		heartBeatBuf.flip();
		try {
			socket.write(heartBeatBuf);
			heartBeatBuf.clear();
		} catch(IOException e) {
			heartBeatBuf.clear();
			client.stop();
		}
	}
}