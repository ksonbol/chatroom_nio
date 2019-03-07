package client;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Entry point for the client program.
 * 
 * @author Karim Sonbol
 *
 */
public class Main {

	/**
	 * Takes IP address/host name and port number from user and 
	 * creates a new Client object using these values.
	 * 
	 * @param args First value is IP address or host name, second value is port number.
	 */
	public static void main(String[] args) {
		InetAddress ipaddr;
		int port;
		if (args.length != 2) {
			System.out.print("Invalid arguments.\nExample Usage: ");
			System.out.println("java client.Main <server IP address> <port number>");
			return;
		}
		try {
			ipaddr = InetAddress.getByName(args[0]);
			port = Integer.parseInt(args[1]);
		}
		catch (UnknownHostException e) {
			System.out.println(e);
			System.out.println("Wrong IP address used.");
			return;
		}		
		Client client = new Client(ipaddr, port);
		client.start();
	}
}
