package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * Entry point for the server program.
 * 
 * @author Karim Sonbol
 *
 */
public class Main {

	/**
	 * Starts the server program by infinitely listening to client connections.
	 * It listens on the specified port, and infinitely waits for client connections.
	 * Once a client tries to connects, it accepts the connection, and calls
	 * {@link Server#newConnection(Socket)} method.
	 * 
	 * @param args an optional argument for the port number. If not given, defaults to port 4444.
	 */
	public static void main(String[] args) throws IOException {
		int port = 4444;
		if (args.length > 0) {
			port = Integer.parseInt(args[0]);
		}
		
		Server server = new Server(port);
		server.start();
	}
}
